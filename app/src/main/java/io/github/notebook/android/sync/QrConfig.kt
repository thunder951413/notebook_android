package io.github.notebook.android.sync

import com.google.gson.JsonParser

/**
 * QR payload shared with Notebook for macOS.
 *
 * v1 (legacy): password-based auth, `password` is required.
 * v2 (current): adds `privateKeyPath` + `privateKeyPassphrase`; password is
 *   optional. At least one of `password` or `privateKeyPath` must be present.
 *
 * {"type":"notebook-sync","version":2,"host":"192.168.1.2","port":22,
 *  "username":"name","path":"~/NotebookSync","fingerprint":"SHA256:...",
 *  "privateKeyPath":"...","privateKeyPassphrase":"..."}
 */
fun parseSyncQrConfig(payload:String):SshSettings {
    val root=runCatching{JsonParser.parseString(payload.trim()).asJsonObject}
        .getOrElse{throw IllegalArgumentException("这不是有效的 Notebook 配置二维码")}
    require(root.get("type")?.asString=="notebook-sync"){"二维码类型不正确"}
    val version=root.get("version")?.asInt?:1
    require(version in 1..2){"不支持这个版本的配置二维码"}
    fun required(name:String,label:String)=root.get(name)?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty()
        .also{require(it.isNotEmpty()){"二维码缺少$label"}}
    val host=required("host","服务器地址")
    val username=(root.get("username")?:root.get("user"))?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty()
    require(username.isNotEmpty()){"二维码缺少用户名"}
    val port=root.get("port")?.asInt?:22
    require(port in 1..65535){"二维码中的端口无效"}
    val password=root.get("password")?.takeUnless{it.isJsonNull}?.asString.orEmpty()
    val path=root.get("path")?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty().ifBlank{"~/NotebookSync"}
    val fingerprint=root.get("fingerprint")?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty()
    require(fingerprint.isEmpty()||fingerprint.startsWith("SHA256:")){"二维码中的 SSH 指纹格式无效"}
    val privateKeyPath=if(version>=2)root.get("privateKeyPath")?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty()else""
    val privateKeyPassphrase=if(version>=2)root.get("privateKeyPassphrase")?.takeUnless{it.isJsonNull}?.asString.orEmpty()else""
    require(password.isNotEmpty()||privateKeyPath.isNotEmpty()){"二维码缺少密码或私钥路径"}
    return SshSettings(host,port,username,password,path,fingerprint,privateKeyPath,privateKeyPassphrase)
}
