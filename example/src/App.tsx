import { useState, useRef, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  FlatList,
  Image,
  TouchableOpacity,
} from 'react-native';
import { TeleImage } from 'react-native-tele-image';
import type { NativeSyntheticEvent } from 'react-native';

// ── 50 real 4K photos from picsum.photos (3840×2160) ──────────────────────────
const IMAGES_4K = Array.from({ length: 50 }, (_, i) => ({
  id: String(i),
  uri: `https://picsum.photos/3840/2160?random=${i + 1}`,
  label: `4K Photo #${i + 1}`,
  resolution: '3840×2160',
}));

// One intentionally broken URL to test onError
const IMAGES_WITH_ERROR = [
  {
    id: 'error-test',
    uri: 'https://invalid.example.com/does-not-exist.jpg',
    label: '❌ Error Test',
    resolution: 'N/A',
  },
  ...IMAGES_4K.slice(0, 49),
];

type ImageItem = (typeof IMAGES_4K)[number];
type Mode = 'super' | 'native' | 'split';

// Track event state per image
type EventStatus = {
  loaded: boolean;
  error: string | null;
  width?: number;
  height?: number;
};

function EventBadge({ status }: { status: EventStatus }) {
  if (status.error) {
    return (
      <View style={styles.badge}>
        <Text style={styles.badgeError}>❌ {status.error}</Text>
      </View>
    );
  }
  if (status.loaded) {
    return (
      <View style={styles.badge}>
        <Text style={styles.badgeSuccess}>
          ✅ {status.width}×{status.height}
        </Text>
      </View>
    );
  }
  return (
    <View style={styles.badge}>
      <Text style={styles.badgePending}>⏳ Loading</Text>
    </View>
  );
}

export default function App() {
  const [mode, setMode] = useState<Mode>('super');
  const [events, setEvents] = useState<Record<string, EventStatus>>({});
  const listRef = useRef<FlatList<ImageItem>>(null);

  useEffect(() => {
    listRef.current?.scrollToOffset({ offset: 0, animated: false });
    setEvents({});
  }, [mode]);

  const handleLoad = useCallback(
    (
      id: string,
      event: NativeSyntheticEvent<{
        width: number;
        height: number;
        source: string;
      }>
    ) => {
      const { width, height, source } = event.nativeEvent;
      console.log(`[onLoad] id=${id} ${width}×${height} source=${source}`);
      setEvents((prev) => ({
        ...prev,
        [id]: { loaded: true, error: null, width, height },
      }));
    },
    []
  );

  const handleError = useCallback(
    (
      id: string,
      event: NativeSyntheticEvent<{ error: string; source: string }>
    ) => {
      const { error, source } = event.nativeEvent;
      console.warn(`[onError] id=${id} error=${error} source=${source}`);
      setEvents((prev) => ({
        ...prev,
        [id]: { loaded: false, error },
      }));
    },
    []
  );

  const data = mode === 'super' ? IMAGES_WITH_ERROR : IMAGES_4K;

  const renderItem = ({ item, index }: { item: ImageItem; index: number }) => {
    const status = events[item.id] || {
      loaded: false,
      error: null,
    };

    if (mode === 'split') {
      return (
        <View style={styles.card}>
          <View style={styles.splitRow}>
            <View style={styles.splitHalf}>
              <TeleImage
                source={{ uri: item.uri }}
                style={styles.splitImage}
                resizeMode="cover"
                fadeDuration={150}
                onLoad={(e) => handleLoad(item.id, e)}
                onError={(e) => handleError(item.id, e)}
              />
              <Text style={styles.splitLabel}>TeleImage</Text>
            </View>
            <View style={styles.splitHalf}>
              <Image
                source={{ uri: item.uri }}
                style={styles.splitImage}
                resizeMode="cover"
              />
              <Text style={styles.splitLabel}>RN Image</Text>
            </View>
          </View>
          <View style={styles.labelBg}>
            <Text style={styles.label}>{item.label}</Text>
            <Text style={styles.labelSub}>
              #{index + 1} · {item.resolution} · Side by side
            </Text>
            <EventBadge status={status} />
          </View>
        </View>
      );
    }

    if (mode === 'super') {
      return (
        <View style={styles.card}>
          <TeleImage
            source={{ uri: item.uri }}
            style={styles.image}
            resizeMode="cover"
            fadeDuration={150}
            priority={index < 5 ? 'high' : 'normal'}
            onLoad={(e) => handleLoad(item.id, e)}
            onError={(e) => handleError(item.id, e)}
          />
          <View style={styles.labelBg}>
            <Text style={styles.label}>{item.label}</Text>
            <Text style={styles.labelSub}>
              #{index + 1} · {item.resolution} · TeleImage
            </Text>
            <EventBadge status={status} />
          </View>
        </View>
      );
    }

    // native mode
    return (
      <View style={styles.card}>
        <Image
          source={{ uri: item.uri }}
          style={styles.image}
          resizeMode="cover"
        />
        <View style={styles.labelBg}>
          <Text style={styles.label}>{item.label}</Text>
          <Text style={styles.labelSub}>
            #{index + 1} · {item.resolution} · RN Image
          </Text>
        </View>
      </View>
    );
  };

  // Count events
  const loadCount = Object.values(events).filter((e) => e.loaded).length;
  const errorCount = Object.values(events).filter((e) => e.error).length;

  return (
    <View style={styles.root}>
      {/* Mode selector */}
      <View style={styles.tabBar}>
        <TouchableOpacity
          style={[styles.tab, mode === 'super' && styles.tabActive]}
          onPress={() => setMode('super')}
        >
          <Text
            style={[styles.tabText, mode === 'super' && styles.tabTextActive]}
          >
            ⚡ TeleImage
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tab, mode === 'native' && styles.tabActive]}
          onPress={() => setMode('native')}
        >
          <Text
            style={[styles.tabText, mode === 'native' && styles.tabTextActive]}
          >
            📦 RN Image
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.tab, mode === 'split' && styles.tabActive]}
          onPress={() => setMode('split')}
        >
          <Text
            style={[styles.tabText, mode === 'split' && styles.tabTextActive]}
          >
            🔀 Split
          </Text>
        </TouchableOpacity>
      </View>

      <FlatList
        ref={listRef}
        data={data}
        keyExtractor={(item) => `${mode}-${item.id}`}
        renderItem={renderItem}
        contentContainerStyle={styles.container}
        ListHeaderComponent={
          <View style={styles.header}>
            <Text style={styles.title}>Event Test 🧪</Text>
            <Text style={styles.subtitle}>
              onLoad · onError{'\n'}
              {mode === 'super'
                ? `⚡ TeleImage — ✅ ${loadCount} loaded · ❌ ${errorCount} errors`
                : mode === 'native'
                  ? '📦 RN Image — no TeleImage events'
                  : `🔀 Split — ✅ ${loadCount} loaded · ❌ ${errorCount} errors`}
            </Text>
            <Text style={styles.hint}>
              Check Metro console for [onLoad] [onError] logs
            </Text>
          </View>
        }
        initialNumToRender={3}
        maxToRenderPerBatch={5}
        windowSize={3}
        removeClippedSubviews
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#0f0f14',
  },
  tabBar: {
    flexDirection: 'row',
    paddingTop: 56,
    paddingHorizontal: 16,
    paddingBottom: 12,
    backgroundColor: '#16161e',
    gap: 8,
  },
  tab: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#1e1e2a',
    alignItems: 'center',
  },
  tabActive: {
    backgroundColor: '#3a86ff',
  },
  tabText: {
    color: '#888',
    fontSize: 13,
    fontWeight: '600',
  },
  tabTextActive: {
    color: '#fff',
  },
  container: {
    paddingVertical: 16,
    paddingHorizontal: 16,
  },
  header: {
    alignItems: 'center',
    marginBottom: 24,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: '#fff',
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 14,
    color: '#888',
    textAlign: 'center',
    lineHeight: 20,
  },
  hint: {
    fontSize: 12,
    color: '#555',
    textAlign: 'center',
    marginTop: 8,
    fontStyle: 'italic',
  },
  card: {
    marginBottom: 16,
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: '#1a1a24',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 6,
  },
  image: {
    width: '100%',
    height: 240,
  },
  splitRow: {
    flexDirection: 'row',
  },
  splitHalf: {
    flex: 1,
  },
  splitImage: {
    width: '100%',
    height: 200,
  },
  splitLabel: {
    color: '#aaa',
    fontSize: 10,
    textAlign: 'center',
    paddingVertical: 4,
    backgroundColor: '#12121a',
    fontWeight: '600',
  },
  labelBg: {
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  label: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  labelSub: {
    color: '#666',
    fontSize: 12,
    marginTop: 2,
  },
  badge: {
    marginTop: 6,
  },
  badgeSuccess: {
    color: '#4ade80',
    fontSize: 11,
    fontWeight: '600',
  },
  badgeError: {
    color: '#f87171',
    fontSize: 11,
    fontWeight: '600',
  },
  badgePending: {
    color: '#555',
    fontSize: 11,
    fontWeight: '600',
  },
});
