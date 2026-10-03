package se.kjellstrand.markera.ui.suggestion

import platform.Foundation.NSBundle

actual val appPlatform: String = "ios"

actual val appVersion: String? =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
