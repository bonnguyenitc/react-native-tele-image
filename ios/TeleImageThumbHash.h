/*
 * TeleImageThumbHash.h — iOS/Objective-C ThumbHash DCT decoder
 *
 * Ported from ThumbHashDecoder.kt (Phase 1) and the reference JS implementation
 * at https://evanw.github.io/thumbhash/
 *
 * Phase 3: Full DCT decode for iOS to match Android quality.
 * Generates a 32×32 ARGB bitmap from a ThumbHash byte array.
 */

#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface TeleImageThumbHash : NSObject

/**
 * Decode a ThumbHash byte array to a UIImage (32×32 px).
 * Returns nil if the hash is invalid or too short.
 *
 * @param hashData  raw bytes of the ThumbHash (base64-decoded)
 */
+ (nullable UIImage *)decodeFromData:(NSData *)hashData;

/**
 * Convenience: decode from a base64-encoded NSString.
 */
+ (nullable UIImage *)decodeFromBase64:(NSString *)base64String;

@end

NS_ASSUME_NONNULL_END
