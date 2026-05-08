<p align="center">
  <img src="https://img.shields.io/npm/v/react-native-tele-image?style=flat-square&color=0088cc" alt="npm" />
  <img src="https://img.shields.io/badge/platforms-iOS%20%7C%20Android-brightgreen?style=flat-square" alt="Platforms" />
  <img src="https://img.shields.io/badge/architecture-Fabric%20(New%20Arch)-blue?style=flat-square" alt="New Architecture" />
  <img src="https://img.shields.io/badge/license-GPL--2.0-blue?style=flat-square" alt="License: GPL-2.0" />
</p>

<h1 align="center">react-native-tele-image</h1>

<p align="center">
  <strong>Telegram-grade image rendering for React Native.</strong><br/>
  Native rendering pipelines (Canvas / CALayer, layered caches, ThumbHash) inspired by <a href="https://github.com/TelegramMessenger/Telegram-iOS">Telegram's open-source clients</a> — not a thin wrapper around stock <code>UIImageView</code> or <code>ImageView</code>.
</p>

<p align="center">
  <em>No heavy JS image stack for the final draw — the hot path stays native.</em>
</p>

---

## Table of contents

- [Why TeleImage?](#why-teleimage)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [API](#api)
- [Prefetch & cache](#prefetch--cache)
- [Architecture](#architecture)
- [Lineage & license](#lineage--license)
- [Contributing](#contributing)

---

## Why TeleImage?

React Native's `<Image>` works well for many screens, but large grids (galleries, chat attachments, photo feeds) often show long blank frames, GC jank, and memory pressure.

**TeleImage** targets the same class of optimizations Telegram uses: near-instant ThumbHash placeholders, background decode and caching, bitmap pooling on Android, and releasing backing stores when list cells recycle.

| | `<Image>` (RN) | `<TeleImage>` |
| --- | --- | --- |
| **Drawing** | UIImageView / ImageView | CALayer / Canvas + BitmapShader |
| **Placeholder** | Often empty until load | ThumbHash decoded in sub‑millisecond time |
| **Memory** | Limited bitmap reuse control | BitmapPool + LRU (Android), budgeted caches (iOS) |
| **Fade** | Often JS `Animated` | Native crossfade (fewer bridge trips for the transition) |

---

## Features

- **ThumbHash / BlurHash strings** — blurred preview without waiting on the network.
- **Download priority (`priority`)** — mark on-screen images as `high`.
- **`resizeMode`** — `cover` | `contain` | `stretch`.
- **Crossfade** — `fadeDuration` in ms; use `0` to disable. JS default is **150ms**.
- **Native rendering** — Android: Canvas + shader; iOS: CALayer / `CGImageSource` style pipeline (not the default ImageView path).
- **List-friendly** — Fabric `prepareForRecycle` drops bitmap / backing when cells leave the viewport.
- **Prefetch (Android)** — native `TeleImageModule` can warm disk + memory cache (see below).

Lower-level details (HTTP/2, SHA-256 disk keys, etc.) live in source comments and evolve per release.

---

## Requirements

- **React Native New Architecture (Fabric)** — uses Codegen; **Legacy Architecture is not supported**.
- **Recommended**: React Native **≥ 0.76** (the example app in this repo uses **0.85**).
- **iOS & Android** — standard CocoaPods / Gradle setup for native modules.

---

## Installation

```sh
npm install react-native-tele-image
# or
yarn add react-native-tele-image
```

**iOS** — install pods in your app:

```sh
cd ios && pod install
```

Rebuild your app. For monorepos or Expo prebuild, follow your usual native-module workflow.

---

## Quick start

```tsx
import { TeleImage } from 'react-native-tele-image';

export function Banner() {
  return (
    <TeleImage
      source={{
        uri: 'https://images.unsplash.com/photo-1506905925346-21bda4d32df4?w=800',
        thumbhash: 'HBkSHYSIeHiPiHh4eJd4eFeEd3',
      }}
      style={{ width: 300, height: 200, borderRadius: 12 }}
      priority="high"
      resizeMode="cover"
      fadeDuration={150}
      onLoad={({ nativeEvent }) =>
        console.log(`Loaded ${nativeEvent.width}×${nativeEvent.height}`)
      }
      onError={({ nativeEvent }) =>
        console.warn('Failed:', nativeEvent.error)
      }
    />
  );
}
```

**Tip:** Generate a ThumbHash (~25–30 character base64-ish compact string) from the source image or return it from your API with image metadata — see [ThumbHash](https://evanw.github.io/thumbhash/).

---

## API

### `TeleImage`

| Prop | Type | Default | Description |
| --- | --- | --- | --- |
| `source` | `{ uri: string; thumbhash?: string }` | **required** | Remote URL and optional ThumbHash |
| `style` | `ViewStyle` | — | Layout: width, height, `borderRadius`, etc. |
| `resizeMode` | `'cover' \| 'contain' \| 'stretch'` | `'cover'` | How the image fits the view |
| `priority` | `'high' \| 'normal' \| 'low'` | `'normal'` | Download scheduling priority |
| `fadeDuration` | `number` | `150` | Crossfade ms; `0` disables |
| `onLoad` | `(e) => void` | — | `nativeEvent`: `{ width, height, source }` |
| `onError` | `(e) => void` | — | `nativeEvent`: `{ error, source }` |

### Exported types

```ts
import type {
  TeleImageProps,
  TeleImageSource,
  ImagePriority,
  ImageResizeMode,
  OnLoadEvent,
  OnErrorEvent,
} from 'react-native-tele-image';
```

---

## Prefetch & cache

### `TeleImagePrefetch`

Prefetch the next page before the user scrolls:

```ts
import { TeleImagePrefetch } from 'react-native-tele-image';

await TeleImagePrefetch.prefetch([
  { uri: 'https://example.com/a.jpg' },
  { uri: 'https://example.com/b.jpg', priority: 'low' },
]);

TeleImagePrefetch.cancel('https://example.com/a.jpg');
TeleImagePrefetch.cancelAll();
```

**Android:** When `NativeModules.TeleImageModule` is present, calls delegate to native (OkHttp + caches).

**iOS:** There is **no** equivalent native module in this package yet — prefetch may be a **no-op** until iOS support lands.

### `TeleImageCache`

Public JS API:

```ts
import { TeleImageCache } from 'react-native-tele-image';

await TeleImageCache.clearMemory();
await TeleImageCache.clearDisk();
await TeleImageCache.clear();

const size = await TeleImageCache.getSize();
// { memory: number; disk: number; bitmapPool?: number }
```

**Current status:** The TypeScript implementation is a **stub** (resolved promises / zero sizes). **Android** already implements `clearMemory`, `clearDisk`, and `getSize` on `TeleImageModule`. Until the stub is wired, call `NativeModules.TeleImageModule` on Android if you need real cache control.

---

## Architecture

```
┌─────────────────────────────────────────┐
│ JavaScript / TypeScript                 │
│ TeleImage · TeleImagePrefetch · Cache   │
└──────────────────┬──────────────────────┘
                   │ Fabric (Codegen)
┌──────────────────▼──────────────────────┐
│ Native                                  │
│  iOS: TeleImageView (+ ThumbHash, …)    │
│  Android: TeleImageView + HttpTask,     │
│           MemCache, BitmapPool, Module  │
└─────────────────────────────────────────┘
```

For line-by-line lineage to Telegram sources, see comments and native code in the repository.

---

## Lineage & license

Algorithms and caching ideas are **ported or referenced** from Telegram's open-source Android/iOS clients.
This project uses the same license as Telegram's official clients.

**Package license:** [GNU General Public License v2.0](LICENSE) (**GPL-2.0**) — the same license used by
[Telegram for Android](https://github.com/DrKLO/Telegram) and [Telegram for iOS](https://github.com/TelegramMessenger/Telegram-iOS).

> This program is free software; you can redistribute it and/or modify it under the terms of the
> GNU General Public License as published by the Free Software Foundation; either **version 2** of the
> License, or (at your option) any later version.
>
> This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**;
> without even the implied warranty of **MERCHANTABILITY** or **FITNESS FOR A PARTICULAR PURPOSE**.
> See the [GNU General Public License](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) for more details.

_Telegram is a trademark of Telegram FZ-LLC._

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for workflow, pull requests, and community guidelines.

**Repository:** [github.com/bonnguyenitc/react-native-tele-image](https://github.com/bonnguyenitc/react-native-tele-image)

---

<p align="center">
  Built for smooth galleries and photo-heavy feeds — New Architecture, with a Telegram-shaped rendering mindset.
</p>
