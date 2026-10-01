package io.github.notebook.android.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Wire-compatible with the Electron Web Crypto private journal envelope. */
internal class WebdavPrivacyCipher(private val secret:String) {
    companion object {
        const val MARKER="\$encrypted"
        const val ITERATIONS=310_000
        fun isEncrypted(payload:JsonObject)=payload.has(MARKER)
    }
    private val random=SecureRandom()
    private val salt=ByteArray(16).also(random::nextBytes)
    private val keys=mutableMapOf<String,SecretKeySpec>()
    private fun key(salt:ByteArray):SecretKeySpec {
        require(secret.isNotEmpty()){ "请配置两端相同的隐私同步密钥" }
        val name=Base64.getEncoder().encodeToString(salt)
        return keys[name]?:run {
            val spec=PBEKeySpec(secret.toCharArray(),salt,ITERATIONS,256)
            val derived=try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}
            if(keys.size>=32)keys.clear()
            SecretKeySpec(derived,"AES").also{keys[name]=it}
        }
    }
    fun encrypt(context:String,bytes:ByteArray,salt:ByteArray=this.salt,iv:ByteArray=ByteArray(12).also(random::nextBytes)):JsonObject {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(salt),GCMParameterSpec(128,iv))
        cipher.updateAAD("notebook-webdav-private-v1\u0000$context".toByteArray(Charsets.UTF_8))
        val encoder=Base64.getEncoder()
        return JsonObject().apply{add(MARKER,JsonObject().apply{
            addProperty("version",1);addProperty("algorithm","AES-256-GCM");addProperty("kdf","PBKDF2-SHA256");addProperty("iterations",ITERATIONS)
            addProperty("salt",encoder.encodeToString(salt));addProperty("iv",encoder.encodeToString(iv));addProperty("ciphertext",encoder.encodeToString(cipher.doFinal(bytes)))
        })}
    }
    fun decrypt(context:String,payload:JsonObject):ByteArray {
        val envelope=payload[MARKER]?.takeIf{it.isJsonObject}?.asJsonObject?:error("隐私同步密文格式不正确")
        require(envelope["version"]?.asInt==1 && envelope["algorithm"]?.asString=="AES-256-GCM" && envelope["kdf"]?.asString=="PBKDF2-SHA256" && envelope["iterations"]?.asInt==ITERATIONS){"不支持的隐私同步密文格式"}
        val decoder=Base64.getDecoder()
        val salt=decoder.decode(envelope["salt"].asString);val iv=decoder.decode(envelope["iv"].asString);val ciphertext=decoder.decode(envelope["ciphertext"].asString)
        require(salt.size==16 && iv.size==12 && ciphertext.size>=16){"隐私同步密文格式不正确"}
        val key=key(salt)
        return try{
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv))
            cipher.updateAAD("notebook-webdav-private-v1\u0000$context".toByteArray(Charsets.UTF_8));cipher.doFinal(ciphertext)
        }catch(error:java.security.GeneralSecurityException){throw IllegalArgumentException("隐私同步密钥不匹配或密文损坏；数据和同步位置已保留",error)}
    }
    fun encryptPayload(type:String,id:String,payload:JsonObject,operation:String="upsert",version:Long=0)=encrypt("$type:$id:$operation:$version",payload.toString().toByteArray(Charsets.UTF_8))
    fun decryptPayload(type:String,id:String,payload:JsonObject,operation:String="upsert",version:Long=0)=JsonParser.parseString(String(decrypt("$type:$id:$operation:$version",payload),Charsets.UTF_8)).asJsonObject
}
