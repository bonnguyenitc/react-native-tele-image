package com.teleimage

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.TeleImageViewManagerInterface
import com.facebook.react.viewmanagers.TeleImageViewManagerDelegate

/**
 * TeleImageViewManager — Fabric ViewManager for TeleImageView.
 *
 * Binds React Native props to the native TeleImageView.
 * Priority prop maps to Telegram's FileLoader priority constants:
 *   "high"   → PRIORITY_HIGH   = 3  (FileLoader.java line 36)
 *   "normal" → PRIORITY_NORMAL = 1  (FileLoader.java line 38)
 *   "low"    → PRIORITY_LOW    = 0  (FileLoader.java line 39)
 */
@ReactModule(name = TeleImageViewManager.NAME)
class TeleImageViewManager :
    SimpleViewManager<TeleImageView>(),
    TeleImageViewManagerInterface<TeleImageView> {

    companion object {
        const val NAME = "TeleImageView"
    }

    private val delegate = TeleImageViewManagerDelegate(this)

    override fun getDelegate(): ViewManagerDelegate<TeleImageView> = delegate
    override fun getName(): String = NAME

    override fun createViewInstance(context: ThemedReactContext): TeleImageView =
        TeleImageView(context)

    // ── Props ─────────────────────────────────────────────────────────────────

    @ReactProp(name = "uri")
    override fun setUri(view: TeleImageView, value: String?) {
        view.uri = value
    }

    @ReactProp(name = "thumbhash")
    override fun setThumbhash(view: TeleImageView, value: String?) {
        view.thumbhash = value
    }

    /**
     * Priority: maps to Telegram FileLoader priority constants
     * FileLoader.java lines 36-39: PRIORITY_HIGH=3, PRIORITY_NORMAL=1, PRIORITY_LOW=0
     */
    @ReactProp(name = "priority")
    override fun setPriority(view: TeleImageView, value: String?) {
        view.priority = when (value) {
            "high"   -> TeleImageHttpTask.PRIORITY_HIGH
            "low"    -> TeleImageHttpTask.PRIORITY_LOW
            else     -> TeleImageHttpTask.PRIORITY_NORMAL
        }
    }

    @ReactProp(name = "resizeMode")
    override fun setResizeMode(view: TeleImageView, value: String?) {
        view.resizeMode = value ?: "cover"
    }

    @ReactProp(name = "fadeDuration", defaultDouble = TeleImageView.DEFAULT_CROSSFADE_DURATION.toDouble())
    override fun setFadeDuration(view: TeleImageView, value: Double) {
        view.fadeDuration = value.toInt()
    }

    // ── Events ────────────────────────────────────────────────────────────────

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any>? {
        val base = super.getExportedCustomDirectEventTypeConstants() ?: mutableMapOf()
        base["topLoad"] = mapOf("registrationName" to "onLoad")
        base["topError"] = mapOf("registrationName" to "onError")
        return base
    }
}
