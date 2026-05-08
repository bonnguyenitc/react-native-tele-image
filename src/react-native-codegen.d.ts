/**
 * Type declarations for react-native internal codegen modules.
 *
 * The `declare module` blocks in react-native/types/modules/Codegen.d.ts
 * are not picked up by `moduleResolution: "bundler"` because bundler mode
 * only resolves via `exports` map in package.json, not ambient declarations.
 *
 * These re-declarations make the deep import paths resolvable for TypeScript
 * while codegen's AST parser uses them to identify opaque types (Double, Int32, etc.)
 * instead of resolving local type aliases back to TSNumberKeyword.
 */

declare module 'react-native/Libraries/Types/CodegenTypes' {
  import type { NativeSyntheticEvent } from 'react-native';

  export type BubblingEventHandler<
    T,
    PaperName extends string | never = never,
  > = (event: NativeSyntheticEvent<T>) => void | Promise<void>;
  export type DirectEventHandler<
    T,
    PaperName extends string | never = never,
  > = (event: NativeSyntheticEvent<T>) => void | Promise<void>;

  export type Double = number;
  export type Float = number;
  export type Int32 = number;

  type DefaultTypes = number | boolean | string | ReadonlyArray<string>;
  export type WithDefault<
    Type extends DefaultTypes,
    Value extends Type | string | undefined | null,
  > = Type | undefined | null;
}

declare module 'react-native/Libraries/Utilities/codegenNativeComponent' {
  import type { HostComponent } from 'react-native';

  export interface Options {
    readonly interfaceOnly?: boolean | undefined;
    readonly paperComponentName?: string | undefined;
    readonly paperComponentNameDeprecated?: string | undefined;
    readonly excludedPlatforms?: ReadonlyArray<'iOS' | 'android'> | undefined;
  }

  export type NativeComponentType<T> = HostComponent<T>;

  function codegenNativeComponent<Props extends object>(
    componentName: string,
    options?: Options,
  ): NativeComponentType<Props>;

  export default codegenNativeComponent;
}
