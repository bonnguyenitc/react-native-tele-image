#import <React/RCTViewComponentView.h>
#import <UIKit/UIKit.h>

#ifndef TeleImageViewNativeComponent_h
#define TeleImageViewNativeComponent_h

NS_ASSUME_NONNULL_BEGIN

/**
 * TeleImageView (iOS) — Phase 3 implementation.
 *
 * Architecture mirrors Telegram-iOS source code (verified line-by-line):
 *
 * ┌─ TransformImageNode.swift ─────────────────────────────────────────────────┐
 * │  Signal pipeline: combineLatest → deliverOn(concurrentDefaultQueue())      │
 * │  → mapToThrottled { DrawingContext } → deliverOnMainQueue → layer.contents │
 * │  Crossfade: animateAlpha(from: 0, to: 1, duration: 0.15)                  │
 * │  removeOnCompletion: true (default)                                        │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ TGMemoryImageCache.m ─────────────────────────────────────────────────────┐
 * │  LRU dict with softLimit/hardLimit, timestamp-sorted eviction.             │
 * │  Cost: w*scale * h*scale * 4. Old entry cost subtracted on overwrite.      │
 * │  All mutations on serial queue ([SQueue dispatchSync:]).                    │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ DirectMediaImageCache.swift ──────────────────────────────────────────────┐
 * │  DrawingContext at exact display size (downsample). Async via Signal.       │
 * │  runOn(.concurrentDefaultQueue()) for background decode.                   │
 * │  immediateThumbnailData decoded sync on main thread for placeholder.       │
 * └────────────────────────────────────────────────────────────────────────────┘
 *
 * Our adaptations for React Native Fabric:
 *   1. CGImageSource replaces DrawingContext (ImageIO built-in downsample)
 *   2. uint64_t generation counter replaces MetaDisposable
 *   3. dispatch_barrier_async for disk write serialization
 *   4. ThumbHash replaces TinyThumbnail (full DCT, 32×32 px)
 */
@interface TeleImageView : RCTViewComponentView

@end

NS_ASSUME_NONNULL_END

#endif /* TeleImageViewNativeComponent_h */
