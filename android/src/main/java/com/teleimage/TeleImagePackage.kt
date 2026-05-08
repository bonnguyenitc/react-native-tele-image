package com.teleimage

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class TeleImageViewPackage : BaseReactPackage() {

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(TeleImageViewManager())

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        return when (name) {
            TeleImageModule.NAME -> TeleImageModule(reactContext)
            else -> null
        }
    }

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            TeleImageModule.NAME to ReactModuleInfo(
                /* name */          TeleImageModule.NAME,
                /* className */     TeleImageModule.NAME,
                /* canOverrideExisting */ false,
                /* needsEagerInit */ false,
                /* isCxxModule */   false,
                /* isTurboModule */ true,
            )
        )
    }
}
