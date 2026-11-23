@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.series

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** What a [TokenVault.read] found. Only [NotFound] means "there is no token"; [Failed] is unknown. */
sealed interface VaultRead {
    data class Found(val token: String) : VaultRead
    data object NotFound : VaultRead
    /** [status] is the OSStatus, safe to log; the token never is. */
    data class Failed(val status: Int) : VaultRead
}

/**
 * Where the backend session token lives on iOS: one secret, read, replaced or removed.
 * [write] and [delete] return an OSStatus, `0` for success; [delete] of a missing item is success.
 * [KeychainTokenVault] in the app, a fake in the tests.
 */
interface TokenVault {
    fun read(): VaultRead
    fun write(token: String): Int
    fun delete(): Int
}

/**
 * The token as one Keychain generic password, `AfterFirstUnlockThisDeviceOnly`: not in
 * iCloud/iTunes backups and never moved to another device. A Keychain item outlives an
 * uninstall; [UserDefaultsBackendTokenStore] deletes such a leftover on a fresh install.
 */
class KeychainTokenVault(
    private val service: String = "se.kjellstrand.markera.backend",
    private val account: String = "session-token",
) : TokenVault {

    override fun read(): VaultRead = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { SecItemCopyMatching(it, result.ptr) }
        when (status) {
            errSecSuccess -> {
                val data = CFBridgingRelease(result.value) as? NSData
                val token = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
                if (token.isNullOrEmpty()) VaultRead.Failed(UNREADABLE) else VaultRead.Found(token)
            }
            errSecItemNotFound -> VaultRead.NotFound
            else -> VaultRead.Failed(status)
        }
    }

    /** Delete then add: the item always carries this accessibility, also one an older build wrote. */
    override fun write(token: String): Int {
        val data = NSString.create(string = token).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return UNREADABLE
        delete().let { if (it != errSecSuccess) return it }
        val bridged = CFBridgingRetain(data)
        return try {
            withQuery(
                kSecValueData to bridged,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ) { SecItemAdd(it, null) }
        } finally {
            CFRelease(bridged)
        }
    }

    override fun delete(): Int {
        val status = withQuery { SecItemDelete(it) }
        return if (status == errSecItemNotFound) errSecSuccess else status
    }

    /** The class/service/account query plus [extra], released after [block]. */
    private fun <T> withQuery(vararg extra: Pair<CFTypeRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val svc = CFBridgingRetain(service)
        val acct = CFBridgingRetain(account)
        val query = CFDictionaryCreateMutable(
            null, (3 + extra.size).convert(),
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, svc)
            CFDictionaryAddValue(query, kSecAttrAccount, acct)
            extra.forEach { (k, v) -> CFDictionaryAddValue(query, k, v) }
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(svc)
            CFRelease(acct)
        }
    }

    private companion object {
        /** errSecDecode: the stored bytes were not a UTF-8 token. */
        const val UNREADABLE = -26275
    }
}
