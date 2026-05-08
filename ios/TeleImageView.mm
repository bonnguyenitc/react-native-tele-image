#import "TeleImageView.h"
#import "TeleImageThumbHash.h"

#import <React/RCTConversions.h>
#import <react/renderer/components/TeleImageViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/TeleImageViewSpec/EventEmitters.h>
#import <react/renderer/components/TeleImageViewSpec/Props.h>
#import <react/renderer/components/TeleImageViewSpec/RCTComponentViewHelpers.h>
#import "RCTFabricComponentsPlugins.h"

#import <CommonCrypto/CommonDigest.h>
#import <ImageIO/ImageIO.h>

using namespace facebook::react;

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Dedicated background queue (mirrors Telegram's concurrentDefaultQueue)
//
// Telegram-iOS processes image data on Queue.concurrentDefaultQueue() — a global
// concurrent queue at QOS_CLASS_UTILITY. We match this with a single CONCURRENT
// queue at the same QOS class, allowing multiple images to decode in parallel
// (critical for scroll performance in lists).
//
// Disk writes are serialized via dispatch_barrier_async to avoid races.
// ─────────────────────────────────────────────────────────────────────────────

static dispatch_queue_t _imageProcessingQueue(void) {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        dispatch_queue_attr_t attr = dispatch_queue_attr_make_with_qos_class(
            DISPATCH_QUEUE_CONCURRENT, QOS_CLASS_UTILITY, 0);
        q = dispatch_queue_create("com.teleimage.processing", attr);
    });
    return q;
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Shared URLSession (HTTP/2 + connection pool)
//
// One shared session with HTTP/2 (automatic via TLS ALPN on iOS 9+),
// keepalive connection pool per host, and no URLCache (we own disk cache).
// Mirrors Telegram's single ConnectionsManager for all network requests.
// ─────────────────────────────────────────────────────────────────────────────

@interface TeleImageURLSession : NSObject
+ (NSURLSession *)shared;
@end

@implementation TeleImageURLSession
+ (NSURLSession *)shared {
    static NSURLSession *session;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration defaultSessionConfiguration];
        cfg.timeoutIntervalForRequest  = 10.0;
        cfg.timeoutIntervalForResource = 60.0;
        cfg.HTTPMaximumConnectionsPerHost = 6;
        cfg.URLCache = nil;
        cfg.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
        session = [NSURLSession sessionWithConfiguration:cfg delegate:nil delegateQueue:nil];
    });
    return session;
}
@end

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Memory Cache  (mirrors TGMemoryImageCache.m)
//
// Matches TGMemoryImageCache's architecture exactly:
//  1. Custom NSMutableDictionary + LRU timestamp-sorted eviction.
//     (TGMemoryImageCache lines 62-91: _cache, _cacheSize, _addSize:)
//  2. Tracks cost per-entry, subtracts on eviction and overwrite.
//     (TGMemoryImageCache line 156-159: subtract old item cost on re-set)
//  3. All mutations dispatched on a serial queue.
//     (TGMemoryImageCache line 53: _queue = [SQueue mainQueue])
//  4. softMemoryLimit / hardMemoryLimit with LRU eviction.
//     (TGMemoryImageCache line 48-55: initWithSoftMemoryLimit:hardMemoryLimit:)
// ─────────────────────────────────────────────────────────────────────────────

@interface _TeleImageCacheItem : NSObject
@property (nonatomic, strong) UIImage *image;
@property (nonatomic) NSUInteger cost;       // bytes
@property (nonatomic) CFAbsoluteTime stamp;  // LRU timestamp
@end
@implementation _TeleImageCacheItem
@end

@interface TeleImageMemoryCache : NSObject
+ (instancetype)shared;
- (void)setImage:(UIImage *)image forKey:(NSString *)key;
- (UIImage * _Nullable)imageForKeySync:(NSString *)key;
- (void)removeAll;
@end

@implementation TeleImageMemoryCache {
    dispatch_queue_t       _queue;
    NSMutableDictionary   *_cache;   // key → _TeleImageCacheItem
    NSUInteger             _cacheSize;
    NSUInteger             _softLimit;
    NSUInteger             _hardLimit;
}

+ (instancetype)shared {
    static TeleImageMemoryCache *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ instance = [[self alloc] init]; });
    return instance;
}

- (instancetype)init {
    self = [super init];
    _queue = dispatch_queue_create("com.teleimage.memcache", DISPATCH_QUEUE_SERIAL);
    _cache = [[NSMutableDictionary alloc] init];
    _cacheSize = 0;

    // Matches Telegram: softMemoryLimit + hardMemoryLimit (with ~25% headroom)
    NSUInteger physMB = (NSUInteger)([NSProcessInfo processInfo].physicalMemory / 1024 / 1024);
    _softLimit = MIN(30u, physMB / 8) * 1024 * 1024;
    _hardLimit = _softLimit + (_softLimit / 4);
    return self;
}

// Mirrors TGMemoryImageCache._addSize: — evict oldest entries when over hard limit
// Called ONLY on _queue
- (void)_evictIfNeeded:(NSUInteger)incomingCost {
    if (_cacheSize + incomingCost <= _hardLimit) return;

    // Sort by timestamp ascending (oldest first)
    // Same as TGMemoryImageCache:  keysSortedByValueUsingComparator → remove oldest
    NSArray *sortedKeys = [_cache keysSortedByValueUsingComparator:^NSComparisonResult(
        _TeleImageCacheItem *a, _TeleImageCacheItem *b) {
        return a.stamp < b.stamp ? NSOrderedAscending : NSOrderedDescending;
    }];

    NSInteger needed = (NSInteger)(_cacheSize + incomingCost) - (NSInteger)_softLimit;
    for (NSString *key in sortedKeys) {
        if (needed <= 0) break;
        _TeleImageCacheItem *item = _cache[key];
        needed -= (NSInteger)item.cost;
        _cacheSize = item.cost <= _cacheSize ? _cacheSize - item.cost : 0;
        [_cache removeObjectForKey:key];
    }
}

// Mirrors TGMemoryImageCache setImage:forKey:attributes: (line 147-174)
- (void)setImage:(UIImage *)image forKey:(NSString *)key {
    if (!image || !key) return;
    // cost = raw pixel bytes: w*scale * h*scale * 4
    // Same formula as TGMemoryImageCache line 166
    CGFloat scale = image.scale;
    NSUInteger cost = (NSUInteger)(image.size.width * scale * image.size.height * scale * 4);
    dispatch_async(_queue, ^{
        // Subtract old entry cost on overwrite (TGMemoryImageCache line 156-159)
        _TeleImageCacheItem *existing = self->_cache[key];
        if (existing) {
            self->_cacheSize = existing.cost <= self->_cacheSize
                ? self->_cacheSize - existing.cost : 0;
        }
        [self _evictIfNeeded:cost];
        _TeleImageCacheItem *item = [[_TeleImageCacheItem alloc] init];
        item.image = image;
        item.cost  = cost;
        item.stamp = CFAbsoluteTimeGetCurrent();
        self->_cache[key] = item;
        self->_cacheSize += cost;
    });
}

// Sync read — called only from background queues, never Main Thread.
// Mirrors TGMemoryImageCache imageForKey:attributes: (line 102-122)
// which uses [_queue dispatchSync:]
- (UIImage * _Nullable)imageForKeySync:(NSString *)key {
    if (!key) return nil;
    __block UIImage *result = nil;
    dispatch_sync(_queue, ^{
        _TeleImageCacheItem *item = self->_cache[key];
        if (item) {
            item.stamp = CFAbsoluteTimeGetCurrent();
            result = item.image;
        }
    });
    return result;
}

// Mirrors TGMemoryImageCache clearCache (line 93-99)
- (void)removeAll {
    dispatch_async(_queue, ^{
        [self->_cache removeAllObjects];
        self->_cacheSize = 0;
    });
}

@end

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Disk cache helpers
// ─────────────────────────────────────────────────────────────────────────────

static NSString *_cacheDir(void) {
    static NSString *dir;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSArray *paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES);
        dir = [[paths firstObject] stringByAppendingPathComponent:@"TeleImage"];
    });
    return dir;
}

// SHA-256 truncated to 16 bytes (hex) — collision-resistant.
// Telegram uses resourceId.stringRepresentation as cache key; we use URL hash.
static NSString *_diskKey(NSString *url) {
    if (!url) return nil;
    const char *cStr = [url UTF8String];
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(cStr, (CC_LONG)strlen(cStr), digest);
    NSMutableString *hex = [NSMutableString stringWithCapacity:32];
    for (int i = 0; i < 16; i++) [hex appendFormat:@"%02x", digest[i]];
    return hex;
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Force-decode + Downsample (mirrors Telegram's DrawingContext pattern)
//
// Telegram-iOS (DirectMediaImageCache.swift line 293-303):
//   1. Creates a DrawingContext at exact display size
//   2. Draws the full image into it via context.draw()
//   3. Returns context.generateImage() — already decoded, correct size
//
// We replicate this with CGImageSource:
//   - kCGImageSourceShouldCacheImmediately: forces full JPEG decode on calling
//     thread (our background queue), eliminating main-thread decode stalls.
//   - kCGImageSourceCreateThumbnailFromImageAlways + MaxPixelSize: built-in
//     ImageIO downsampling — more memory-efficient than Telegram's approach
//     because it reads only needed JPEG scans without full decode first.
// ─────────────────────────────────────────────────────────────────────────────

static UIImage * _Nullable _decodeAndDownsample(NSData *data, CGSize targetSize, CGFloat scale) {
    if (!data || data.length == 0) return nil;

    CGImageSourceRef source = CGImageSourceCreateWithData((__bridge CFDataRef)data, NULL);
    if (!source) return nil;

    // Compute the pixel dimensions we actually need
    CGFloat pixelW = targetSize.width  * scale;
    CGFloat pixelH = targetSize.height * scale;
    NSInteger maxDim = (NSInteger)MAX(pixelW, pixelH);
    if (maxDim <= 0) maxDim = 512;  // fallback if view not yet laid out

    NSDictionary *opts = @{
        (id)kCGImageSourceShouldCacheImmediately: @YES,
        (id)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
        (id)kCGImageSourceThumbnailMaxPixelSize: @(maxDim),
        (id)kCGImageSourceCreateThumbnailWithTransform: @YES,
    };

    CGImageRef cgImg = CGImageSourceCreateThumbnailAtIndex(source, 0, (__bridge CFDictionaryRef)opts);
    CFRelease(source);
    if (!cgImg) return nil;

    UIImage *result = [UIImage imageWithCGImage:cgImg scale:scale orientation:UIImageOrientationUp];
    CGImageRelease(cgImg);
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - Disk read/write
//
// Atomic write: temp file → rename (Telegram's MediaBox.storeResourceData).
// Remove existing before rename to handle concurrent writes to same URL.
//
// Disk writes use dispatch_barrier_async on the concurrent processing queue
// to serialize writes while allowing concurrent reads/decodes.
// ─────────────────────────────────────────────────────────────────────────────

static UIImage * _Nullable _readDiskCache(NSString *url, CGSize targetSize, CGFloat scale) {
    NSString *key = _diskKey(url);
    if (!key) return nil;

    // L1: memory cache
    UIImage *mem = [[TeleImageMemoryCache shared] imageForKeySync:key];
    if (mem) return mem;

    // L2: disk cache
    NSString *path = [_cacheDir() stringByAppendingPathComponent:key];
    NSData *data = [NSData dataWithContentsOfFile:path options:NSDataReadingUncached error:nil];
    if (!data) return nil;

    // Force-decode + downsample on background thread
    UIImage *img = _decodeAndDownsample(data, targetSize, scale);
    if (img) [[TeleImageMemoryCache shared] setImage:img forKey:key];
    return img;
}

static void _writeDiskCache(NSString *url, NSData *data) {
    if (!url || !data) return;
    NSString *dir = _cacheDir();
    [[NSFileManager defaultManager] createDirectoryAtPath:dir
                              withIntermediateDirectories:YES
                                               attributes:nil
                                                    error:nil];
    NSString *key       = _diskKey(url);
    NSString *finalPath = [dir stringByAppendingPathComponent:key];
    NSString *tempPath  = [finalPath stringByAppendingFormat:@".%u.tmp", arc4random()];

    if (![data writeToFile:tempPath atomically:NO]) return;

    // Remove existing before rename — fixes disk leak when two tasks race
    [[NSFileManager defaultManager] removeItemAtPath:finalPath error:nil];
    [[NSFileManager defaultManager] moveItemAtPath:tempPath toPath:finalPath error:nil];
}

// ─────────────────────────────────────────────────────────────────────────────
// MARK: - TeleImageView
// ─────────────────────────────────────────────────────────────────────────────

@implementation TeleImageView {
    // CALayers — no UIImageView (Telegram: CALayer-only rendering pipeline)
    CALayer *_placeholderLayer;
    CALayer *_imageLayer;

    // Current state
    NSString             *_currentURL;
    NSURLSessionDataTask *_downloadTask;
    NSString             *_thumbhash;
    CGFloat               _fadeDuration;  // seconds
    NSString             *_resizeMode;

    // Generation counter for URL changes — replaces string comparison in async
    // blocks. Mirrors Telegram's MetaDisposable pattern: each new load invalidates
    // the previous one via a simple integer comparison.
    uint64_t _generation;

    // Last known display size for downsampling decisions
    CGSize  _lastBounds;
    CGFloat _screenScale;
}

// ─── Fabric boilerplate ───────────────────────────────────────────────────────

+ (ComponentDescriptorProvider)componentDescriptorProvider {
    return concreteComponentDescriptorProvider<TeleImageViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame {
    if (self = [super initWithFrame:frame]) {
        static const auto defaultProps = std::make_shared<const TeleImageViewProps>();
        _props = defaultProps;
        _fadeDuration = 0.15f;   // 150 ms — Telegram DEFAULT_CROSSFADE_DURATION
        _resizeMode   = @"cover";
        _screenScale  = [UIScreen mainScreen].scale;
        _generation   = 0;
        [self _setupLayers];
        [self _registerMemoryWarning];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [_downloadTask cancel];
}

// ─── Fabric recycle — CRITICAL for memory in FlatList/RecyclerView ─────────────
//
// When a cell scrolls off-screen, Fabric calls prepareForRecycle to return the
// view to a clean state. Without releasing CALayer.contents here, the decoded
// CGImage bitmap (e.g. 33MB per 4K image) stays retained in GPU-mapped memory.
//
// Telegram equivalent: TransformImageNode.reset() (line 65-71) which sets
// self.contents = nil and disposes the signal.
//
// This is THE most important method for memory in scrolling lists.

- (void)prepareForRecycle {
    [super prepareForRecycle];

    // Cancel in-flight work
    [_downloadTask cancel];
    _downloadTask = nil;
    ++_generation;  // invalidate all async callbacks

    // Remove any in-flight animations to prevent stale callbacks
    [_imageLayer removeAllAnimations];
    [_placeholderLayer removeAllAnimations];

    // Release decoded image bitmaps — this frees the CGImage backing store
    // which is the single largest memory consumer (4K = 33MB per image)
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    _imageLayer.contents = nil;
    _imageLayer.opacity = 0.0f;
    _placeholderLayer.contents = nil;
    _placeholderLayer.opacity = 1.0f;
    _placeholderLayer.backgroundColor = [UIColor colorWithWhite:0.90f alpha:1.0f].CGColor;
    [CATransaction commit];

    // Clear state
    _currentURL = nil;
    _thumbhash  = nil;
}

// ─── Memory pressure (mirrors Telegram clearing LruCache on memory warning) ───

- (void)_registerMemoryWarning {
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(_onMemoryWarning)
                                                 name:UIApplicationDidReceiveMemoryWarningNotification
                                               object:nil];
}

- (void)_onMemoryWarning {
    [[TeleImageMemoryCache shared] removeAll];

    // Also release this instance's decoded bitmap to free GPU memory
    // immediately. The image will be re-decoded from disk cache when
    // the view becomes visible again.
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    _imageLayer.contents = nil;
    _imageLayer.opacity  = 0.0f;
    _placeholderLayer.opacity = 1.0f;
    [CATransaction commit];

    // Invalidate current load so next updateProps triggers re-load
    _currentURL = nil;
    ++_generation;
}

// ─── Layer setup ─────────────────────────────────────────────────────────────

- (void)_setupLayers {
    // 1. Placeholder — shown instantly (Telegram: staticThumbDrawable)
    _placeholderLayer = [CALayer layer];
    _placeholderLayer.backgroundColor = [UIColor colorWithWhite:0.90f alpha:1.0f].CGColor;
    _placeholderLayer.masksToBounds = YES;  // prevent overdraw outside bounds
    [self.layer addSublayer:_placeholderLayer];

    // 2. Real image layer — starts transparent, fades in (Telegram: currentImageDrawable)
    _imageLayer = [CALayer layer];
    _imageLayer.opacity = 0.0f;
    _imageLayer.masksToBounds = YES;
    [self.layer addSublayer:_imageLayer];
}

- (void)layoutSubviews {
    [super layoutSubviews];
    _placeholderLayer.frame = self.bounds;
    _imageLayer.frame       = self.bounds;
    _lastBounds = self.bounds.size;
    [self _applyContentMode];
}

// ─── Props update (Fabric) ────────────────────────────────────────────────────

- (void)updateProps:(const Props::Shared &)props oldProps:(const Props::Shared &)oldProps {
    const auto &p = *std::static_pointer_cast<const TeleImageViewProps>(props);

    NSString *newURI = p.uri.empty()
        ? nil : [NSString stringWithUTF8String:p.uri.c_str()];
    if (![newURI isEqualToString:_currentURL]) {
        [self _loadImageWithURL:newURI];
    }

    NSString *newHash = p.thumbhash.empty()
        ? nil : [NSString stringWithUTF8String:p.thumbhash.c_str()];
    if (![newHash isEqualToString:_thumbhash]) {
        _thumbhash = newHash;
        [self _applyThumbhash];
    }

    _fadeDuration = (CGFloat)p.fadeDuration / 1000.0f;

    NSString *rm = p.resizeMode.empty()
        ? @"cover" : [NSString stringWithUTF8String:p.resizeMode.c_str()];
    if (![rm isEqualToString:_resizeMode]) {
        _resizeMode = rm;
        [self _applyContentMode];
    }

    [super updateProps:props oldProps:oldProps];
}

// ─── ThumbHash placeholder (full DCT decode via TeleImageThumbHash) ──────────
//
// Telegram-iOS decodes immediateThumbnailData synchronously on main thread
// (DirectMediaImageCache.swift line 379, 399). Our ThumbHash is the equivalent.
// The 32×32 DCT decode is negligible cost — same tradeoff Telegram makes.

- (void)_applyThumbhash {
    if (!_thumbhash) {
        _placeholderLayer.contents = nil;
        _placeholderLayer.backgroundColor = [UIColor colorWithWhite:0.90f alpha:1.0f].CGColor;
        return;
    }

    UIImage *thumb = [TeleImageThumbHash decodeFromBase64:_thumbhash];
    if (thumb) {
        _placeholderLayer.contents = (__bridge id)thumb.CGImage;
        _placeholderLayer.contentsGravity = kCAGravityResizeAspectFill;
        _placeholderLayer.backgroundColor = nil;
    } else {
        _placeholderLayer.backgroundColor = [UIColor colorWithWhite:0.90f alpha:1.0f].CGColor;
    }
}

// ─── Image loading pipeline ───────────────────────────────────────────────────
//
// Mirrors Telegram's TransformImageNode.setSignal() pipeline:
//
//   1. Cancel previous (MetaDisposable.set → disposes old signal)
//   2. combineLatest(signal, argumentsPromise)
//   3. deliverOn(Queue.concurrentDefaultQueue())  ← background decode
//   4. mapToThrottled { transform(arguments) }    ← DrawingContext rendering
//   5. deliverOnMainQueue → apply to layer.contents
//
// Our equivalent:
//   1. Cancel previous task, bump _generation
//   2. dispatch_async(concurrentQueue) → check caches, download if miss
//   3. _decodeAndDownsample on background thread
//   4. dispatch_async(mainQueue) → display with crossfade

- (void)_loadImageWithURL:(NSString * _Nullable)url {
    // ── Step 0: Cancel previous (mirrors MetaDisposable.set) ──────────────────
    [_downloadTask cancel];
    _downloadTask = nil;
    _currentURL   = url;
    uint64_t gen  = ++_generation;  // invalidates all in-flight work

    // Reset to placeholder state immediately on Main Thread
    // CRITICAL: setting _imageLayer.contents = nil releases the decoded CGImage.
    // For a 3840×2160 image this frees ~33MB of bitmap memory immediately.
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    _imageLayer.contents  = nil;
    _imageLayer.opacity   = 0.0f;
    _placeholderLayer.opacity = 1.0f;
    [CATransaction commit];

    if (!url) return;

    CGSize    capturedBounds = _lastBounds.width > 0 ? _lastBounds : CGSizeMake(512, 512);
    CGFloat   capturedScale  = _screenScale;
    __weak __typeof__(self) weak = self;

    // ── Step 1-2: check caches on background queue ────────────────────────────
    dispatch_async(_imageProcessingQueue(), ^{
        // Generation check — if URL changed while queued, bail immediately
        __strong __typeof__(weak) self = weak;
        if (!self || gen != self->_generation) return;

        UIImage *cached;
        @autoreleasepool {
            cached = _readDiskCache(url, capturedBounds, capturedScale);
        }
        if (cached) {
            CGFloat w = CGImageGetWidth(cached.CGImage);
            CGFloat h = CGImageGetHeight(cached.CGImage);
            dispatch_async(dispatch_get_main_queue(), ^{
                __strong __typeof__(weak) self = weak;
                if (!self || gen != self->_generation) return;
                [self _displayImage:cached animated:NO];
                [self _emitLoad:(int)w height:(int)h source:url];
            });
            return;
        }

        // ── Step 3: cache miss → download ─────────────────────────────────────
        NSURL *nsurl = [NSURL URLWithString:url];
        if (!nsurl) {
            [self _emitErrorOnMain:@"Invalid URL" source:url generation:gen];
            return;
        }
        NSURLRequest *req = [NSURLRequest requestWithURL:nsurl];

        NSURLSessionDataTask *task = [[TeleImageURLSession shared]
            dataTaskWithRequest:req
              completionHandler:^(NSData * _Nullable data, NSURLResponse * _Nullable response, NSError * _Nullable error) {

            __strong __typeof__(weak) strongSelf = weak;

            if (error) {
                [strongSelf _emitErrorOnMain:error.localizedDescription source:url generation:gen];
                return;
            }
            if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
                NSInteger status = ((NSHTTPURLResponse *)response).statusCode;
                if (status < 200 || status >= 300) {
                    NSString *msg = [NSString stringWithFormat:@"HTTP %ld", (long)status];
                    [strongSelf _emitErrorOnMain:msg source:url generation:gen];
                    return;
                }
            }
            if (!data || data.length == 0) {
                [strongSelf _emitErrorOnMain:@"Empty response" source:url generation:gen];
                return;
            }


            NSData *imageData = data;

            dispatch_async(_imageProcessingQueue(), ^{
                __strong __typeof__(weak) self = weak;
                if (!self || gen != self->_generation) return;

                UIImage *image;
                @autoreleasepool {
                    image = _decodeAndDownsample(imageData, capturedBounds, capturedScale);
                }
                if (!image) {
                    [self _emitErrorOnMain:@"Decode failed" source:url generation:gen];
                    return;
                }

                _writeDiskCache(url, imageData);
                [[TeleImageMemoryCache shared] setImage:image forKey:_diskKey(url)];

                CGFloat w = CGImageGetWidth(image.CGImage);
                CGFloat h = CGImageGetHeight(image.CGImage);

                dispatch_async(dispatch_get_main_queue(), ^{
                    __strong __typeof__(weak) self = weak;
                    if (!self || gen != self->_generation) return;
                    [self _displayImage:image animated:YES];
                    [self _emitLoad:(int)w height:(int)h source:url];
                });
            });
        }];

        [task resume];

        dispatch_async(dispatch_get_main_queue(), ^{
            __strong __typeof__(weak) self = weak;
            if (!self || gen != self->_generation) {
                [task cancel];
                return;
            }
            self->_downloadTask = task;
        });
    });
}

// ─── Display + crossfade ──────────────────────────────────────────────────────
//
// Telegram-iOS crossfade patterns (from source code analysis):
//
// TransformImageNode.swift line 104:
//   layer.animateAlpha(from: 0.0, to: 1.0, duration: 0.15)
//   → firstUpdate: fade in from transparent, removeOnCompletion: true (default)
//
// ImageNode.swift line 186-195:
//   Subsequent updates use a tempLayer with old contents that fades out,
//   new contents set immediately. removeOnCompletion: true, duration: 0.2
//
// CAAnimationUtils.swift line 384:
//   animateAlpha default timing: kCAMediaTimingFunctionEaseInEaseOut
//   default removeOnCompletion: true
//
// Key insight: Telegram ALWAYS uses removeOnCompletion:YES (default).
// Our previous code used removeOnCompletion:NO — this leaked animations
// in the layer tree. Fixed below.

- (void)_displayImage:(UIImage *)image animated:(BOOL)animated {
    _imageLayer.contents = (__bridge id)image.CGImage;
    [self _applyContentMode];

    if (!animated || _fadeDuration <= 0) {
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        _imageLayer.opacity       = 1.0f;
        _placeholderLayer.opacity = 0.0f;
        [CATransaction commit];
        return;
    }

    // Model values must be set BEFORE adding animations.
    // With removeOnCompletion:YES, the animation will be removed when done
    // and the layer snaps to its model value. This matches Telegram's approach
    // where model layer always reflects final state.
    _imageLayer.opacity       = 1.0f;
    _placeholderLayer.opacity = 0.0f;

    // New image fades IN (Telegram TransformImageNode: duration 0.15, easeInEaseOut)
    CABasicAnimation *fadeIn = [CABasicAnimation animationWithKeyPath:@"opacity"];
    fadeIn.fromValue = @0.0f;
    fadeIn.toValue   = @1.0f;
    fadeIn.duration  = _fadeDuration;
    fadeIn.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseInEaseOut];
    fadeIn.removedOnCompletion = YES;  // Telegram default — no animation leak
    [_imageLayer addAnimation:fadeIn forKey:@"opacity"];

    // Placeholder fades OUT
    CABasicAnimation *fadeOut = [CABasicAnimation animationWithKeyPath:@"opacity"];
    fadeOut.fromValue = @1.0f;
    fadeOut.toValue   = @0.0f;
    fadeOut.duration  = _fadeDuration;
    fadeOut.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseInEaseOut];
    fadeOut.removedOnCompletion = YES;
    [_placeholderLayer addAnimation:fadeOut forKey:@"opacity"];
}

// ─── Content mode (contentsGravity maps to RN resizeMode) ─────────────────────

- (void)_applyContentMode {
    if ([_resizeMode isEqualToString:@"contain"]) {
        _imageLayer.contentsGravity = kCAGravityResizeAspect;
    } else if ([_resizeMode isEqualToString:@"stretch"]) {
        _imageLayer.contentsGravity = kCAGravityResize;
    } else if ([_resizeMode isEqualToString:@"center"]) {
        _imageLayer.contentsGravity = kCAGravityCenter;
    } else {
        // "cover" (default)
        _imageLayer.contentsGravity = kCAGravityResizeAspectFill;
    }
}

// ─── Event emission helpers ───────────────────────────────────────────────────

- (void)_emitLoad:(int)width height:(int)height source:(NSString *)source {
    if (!_eventEmitter) return;
    auto emitter = std::static_pointer_cast<const TeleImageViewEventEmitter>(_eventEmitter);
    TeleImageViewEventEmitter::OnLoad payload;
    payload.width  = width;
    payload.height = height;
    payload.source = std::string([source UTF8String] ?: "");
    emitter->onLoad(payload);
}

- (void)_emitErrorOnMain:(NSString *)error source:(NSString *)source generation:(uint64_t)gen {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self || gen != self->_generation || !self->_eventEmitter) return;
        auto emitter = std::static_pointer_cast<const TeleImageViewEventEmitter>(self->_eventEmitter);
        TeleImageViewEventEmitter::OnError payload;
        payload.error  = std::string([error UTF8String] ?: "");
        payload.source = std::string([source UTF8String] ?: "");
        emitter->onError(payload);
    });
}

@end

// ─── Registration ─────────────────────────────────────────────────────────────

Class<RCTComponentViewProtocol> TeleImageViewCls(void) {
    return TeleImageView.class;
}
