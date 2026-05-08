import type { NativeSyntheticEvent, ViewStyle } from 'react-native';
import TeleImageViewNativeComponent from './TeleImageViewNativeComponent';
import type {
  OnLoadEvent,
  OnErrorEvent,
} from './TeleImageViewNativeComponent';

// ─── Public API types ─────────────────────────────────────────────────────────

export type ImagePriority = 'high' | 'normal' | 'low';
export type ImageResizeMode = 'cover' | 'contain' | 'stretch';

export interface TeleImageSource {
  /** Remote image URL */
  uri: string;
  /**
   * ThumbHash or BlurHash compact string (≈25–30 bytes base64).
   * Rendered instantly as a blurred placeholder before the network image loads.
   * Generate at build time or receive from your API alongside image metadata.
   *
   * ThumbHash: https://evanw.github.io/thumbhash/
   */
  thumbhash?: string;
}

export interface TeleImageProps {
  /** Image source (uri + optional thumbhash) */
  source: TeleImageSource;

  /** Layout style applied to the native view */
  style?: ViewStyle;

  /**
   * How to resize the image to fill the view.
   * @default 'cover'
   */
  resizeMode?: ImageResizeMode;

  /**
   * Download priority. Set 'high' for images currently visible on screen.
   * @default 'normal'
   */
  priority?: ImagePriority;

  /**
   * Crossfade duration in milliseconds from placeholder → real image.
   * Set 0 to disable animation.
   * @default 150
   */
  fadeDuration?: number;

  /** Called when the image finishes loading. */
  onLoad?: (event: NativeSyntheticEvent<OnLoadEvent>) => void;

  /** Called when the image fails to load. */
  onError?: (event: NativeSyntheticEvent<OnErrorEvent>) => void;


}

// ─── Export event types for consumers ────────────────────────────────────────
export type { OnLoadEvent, OnErrorEvent };

// ─── Component ────────────────────────────────────────────────────────────────
export { TeleImageViewNativeComponent };
