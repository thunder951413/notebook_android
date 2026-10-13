package io.github.notebook.android.sync

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Plain-language sync failure: what happened and what (if anything) to do. */
fun describeSyncError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(6).toList()
    val message = error.localizedMessage ?: error.message ?: error.javaClass.simpleName
    return when {
        chain.any { it is UnknownHostException || it is ConnectException } ->
            "网络连接失败。联网后会自动重试，本机修改已安全保存。"
        chain.any { it is SocketTimeoutException } ->
            "网络较慢，连接超时。本机修改已安全保存，稍后会自动重试。"
        chain.any { it is SSLException } ->
            "与坚果云的安全连接失败（可能是网络代理或系统时间不正确）。$message"
        message.contains("密钥") || message.contains("AEADBadTag", true) || message.contains("decrypt", true) ->
            "隐私同步密钥与其他设备不一致，私密内容无法解密。请在所有设备上设置相同的隐私同步密钥；在此之前不会覆盖本机内容。"
        else -> message
    }
}
