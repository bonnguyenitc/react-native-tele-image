import type { ViewProps } from 'react-native';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
import type {
  DirectEventHandler,
  Double,
  Int32,
  WithDefault,
} from 'react-native/Libraries/Types/CodegenTypes';

// ─── Event payloads ───────────────────────────────────────────────────────────
export type OnLoadEvent = Readonly<{
  width: Int32;
  height: Int32;
  source: string;
}>;

export type OnErrorEvent = Readonly<{
  error: string;
  source: string;
}>;



// ─── Native Props ─────────────────────────────────────────────────────────────
interface NativeProps extends ViewProps {
  /**
   * Remote URL of the image to load.
   */
  uri?: string;

  /**
   * ThumbHash/BlurHash compact string rendered instantly (before network load).
   * See: https://evanw.github.io/thumbhash/
   */
  thumbhash?: string;

  /**
   * Download priority.
   * - "high"   → visible on screen, load immediately
   * - "normal" → default
   * - "low"    → prefetch / offscreen
   */
  priority?: WithDefault<string, 'normal'>;

  /**
   * How to resize the image to fill the view.
   * Mirrors React Native's <Image resizeMode>.
   */
  resizeMode?: WithDefault<string, 'cover'>;

  /**
   * Cross-fade animation duration (ms) from thumbhash → real image.
   * Set 0 to disable.
   */
  fadeDuration?: WithDefault<Double, 300>;

  /**
   * Called when the image finishes loading successfully.
   */
  onLoad?: DirectEventHandler<OnLoadEvent>;

  /**
   * Called when the image fails to load.
   */
  onError?: DirectEventHandler<OnErrorEvent>;


}

export default codegenNativeComponent<NativeProps>('TeleImageView');
