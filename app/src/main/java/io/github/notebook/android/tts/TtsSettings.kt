package io.github.notebook.android.tts

/** Alibaba Cloud Model Studio (通义千问) speech synthesis flavours the reader can talk to. */
enum class TtsApi(
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val defaultVoice: String,
    val endpointHint: String,
    /** Price per 10,000 characters in yuan, used until the user changes it. */
    val defaultUnitPrice: Float = TtsCost.DEFAULT_UNIT_PRICE,
) {
    Qwen3Flash(
        "千问3-TTS-Flash",
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        "qwen3-tts-flash",
        "Cherry",
        "北京地域的 DashScope 地址；新加坡地域的 Key 需改用对应地域的地址。",
    ),
    QwenAudio(
        "百炼 Audio-TTS",
        "https://<业务空间ID>.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
        "qwen-audio-3.1-tts-flash",
        "longanhuan_v3.1",
        "把 <业务空间ID> 换成百炼控制台里的业务空间 ID，并使用同地域的 API Key。",
    ),
    QianwenAudio(
        "千问AI平台 Audio-TTS",
        "https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/audio/tts/SpeechSynthesizer",
        "qwen-audio-3.0-tts-flash",
        "longanhuan_v3.6",
        "qianwenai.com 平台的北京地域接口，不需要业务空间 ID；也可以直接粘贴文档里的 wss:// 地址，会自动换成对应的 HTTP 地址。API Key 在 platform.qianwenai.com 创建。",
        defaultUnitPrice = 1f,
    );

    /** 千问3-TTS-Flash has its own request shape; the Audio-TTS flavours share one. */
    val isAudioStyle get() = this != Qwen3Flash

    /** Models offered as quick choices; any other name can still be typed. */
    val models get() = if (isAudioStyle) AUDIO_MODELS else listOf(defaultModel)

    /** Largest text the service accepts per request (characters), kept with a margin. */
    val chunkLimit get() = 400
}

val AUDIO_MODELS = listOf("qwen-audio-3.1-tts-flash", "qwen-audio-3.0-tts-flash", "qwen-audio-3.0-tts-plus")

data class TtsVoice(val id: String, val name: String, val note: String)

/** Languages for `language_type` (千问3-TTS-Flash only). */
val TTS_LANGUAGES = listOf(
    "Auto" to "自动", "Chinese" to "中文", "English" to "英语", "Japanese" to "日语", "Korean" to "韩语",
    "French" to "法语", "German" to "德语", "Russian" to "俄语", "Spanish" to "西班牙语", "Italian" to "意大利语", "Portuguese" to "葡萄牙语",
)

data class TtsSettings(
    val api: TtsApi = TtsApi.Qwen3Flash,
    val endpoint: String = TtsApi.Qwen3Flash.defaultEndpoint,
    val apiKey: String = "",
    val model: String = TtsApi.Qwen3Flash.defaultModel,
    val voice: String = TtsApi.Qwen3Flash.defaultVoice,
    val language: String = "Auto",
    /** Natural-language style hint (千问 Audio-TTS `instruction`), e.g. 语气或方言. */
    val instruction: String = "",
    /** Playback speed applied on the phone, 0.5–3.0. */
    val speed: Float = DEFAULT_SPEED,
    /** Price per 10,000 billed characters in yuan, for the estimated cost. */
    val unitPrice: Float = TtsCost.DEFAULT_UNIT_PRICE,
    /** Balance in yuan when counting started; 0 means "don't show a remaining amount". */
    val balance: Float = 0f,
) {
    val configured
        get() = apiKey.isNotBlank() && endpoint.isNotBlank() && !endpoint.contains('<') && model.isNotBlank() && voice.isNotBlank()

    companion object {
        const val DEFAULT_SPEED = 1.25f
        const val MAX_SPEED = 3f
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    }
}

object TtsVoices {
    private fun v(id: String, name: String, note: String) = TtsVoice(id, name, note)

    val qwen3Flash = listOf(
        v("Cherry", "芊悦", "女 · 阳光积极、亲切自然"), v("Serena", "苏瑶", "女 · 温柔"),
        v("Ethan", "晨煦", "男 · 阳光温暖，带北方口音"), v("Chelsie", "千雪", "女 · 二次元虚拟女友"),
        v("Momo", "茉兔", "女 · 撒娇搞怪"), v("Vivian", "十三", "女 · 拽拽的小暴躁"),
        v("Moon", "月白", "男 · 率性帅气"), v("Maia", "四月", "女 · 知性温柔"),
        v("Kai", "凯", "男 · 放松舒缓"), v("Nofish", "不吃鱼", "男 · 设计师风格"),
        v("Bella", "萌宝", "女 · 俏皮小萝莉"), v("Jennifer", "詹妮弗", "女 · 电影质感美式女声"),
        v("Ryan", "甜茶", "男 · 节奏感强、戏剧张力"), v("Katerina", "卡捷琳娜", "女 · 御姐音"),
        v("Aiden", "艾登", "男 · 美式大男孩"), v("Eldric Sage", "沧明子", "男 · 沉稳睿智的老者"),
        v("Mia", "乖小妹", "女 · 温顺乖巧"), v("Mochi", "沙小弥", "男 · 聪明伶俐的小大人"),
        v("Bellona", "燕铮莺", "女 · 洪亮清晰"), v("Vincent", "田叔", "男 · 沙哑烟嗓"),
        v("Bunny", "萌小姬", "女 · 萌系小萝莉"), v("Neil", "阿闻", "男 · 新闻主持风格"),
        v("Elias", "墨讲师", "女 · 严谨善叙事的讲师"), v("Arthur", "徐大爷", "男 · 质朴沧桑的老人"),
        v("Nini", "邻家妹妹", "女 · 软糯甜美"), v("Seren", "小婉", "女 · 温和舒缓，适合助眠"),
        v("Pip", "顽屁小孩", "男 · 调皮童声"), v("Stella", "少女阿月", "女 · 甜美迷糊"),
        v("Bodega", "博德加", "男 · 西班牙大叔"), v("Sonrisa", "索尼莎", "女 · 拉美大姐"),
        v("Alek", "阿列克", "男 · 俄式冷峻与温暖"), v("Dolce", "多尔切", "男 · 慵懒的意大利大叔"),
        v("Sohee", "素熙", "女 · 韩国大姐姐"), v("Ono Anna", "小野杏", "女 · 古灵精怪"),
        v("Lenn", "莱恩", "男 · 理性内敛的德国青年"), v("Emilien", "埃米尔安", "男 · 浪漫的法国大哥哥"),
        v("Andre", "安德雷", "男 · 磁性沉稳"), v("Radio Gol", "拉迪奥·戈尔", "男 · 足球解说风格"),
        v("Jada", "上海-阿珍", "女 · 上海话"), v("Dylan", "北京-晓东", "男 · 北京话"),
        v("Li", "南京-老李", "男 · 南京话"), v("Marcus", "陕西-秦川", "男 · 陕西话"),
        v("Roy", "闽南-阿杰", "男 · 闽南语"), v("Peter", "天津-李彼得", "男 · 天津话"),
        v("Sunny", "四川-晴儿", "女 · 四川话"), v("Eric", "四川-程川", "男 · 四川话"),
        v("Rocky", "粤语-阿强", "男 · 粤语"), v("Kiki", "粤语-阿清", "女 · 粤语"),
    )

    /** qwen-audio-3.1-tts-flash: voice ids carry the _v3.1 suffix. */
    val audio31 = listOf(
        v("longanhuan_v3.1", "龙安欢", "女 · 多语种与方言"), v("longanlingxin_v3.1", "龙安灵心", "女 · 多语种与方言"),
        v("longanfengyue_v3.1", "龙安风悦", "女 · 多语种与方言"), v("xunanchuan_v3.1", "许南川", "男 · 多语种与方言"),
        v("yuxiaoyun_v3.1", "于小云", "女 · 元气亲切，广告、客服、旁白"), v("qiaoxiaojiao_v3.1", "乔小娇", "女 · 俏丽可爱，有声书"),
        v("xiaxiaochen_v3.1", "夏小晨", "女 · 元气明亮"), v("anmingyuan_v3.1", "安明远", "男 · 清亮自然，旁白"),
        v("wenhuaiqing_v3.1", "温怀清", "女 · 清亮柔和，儿童故事、新闻"), v("anxiaolan_v3.1", "安小岚", "女 · 清甜纯净，有声书"),
        v("xieshurou_v3.1", "谢舒柔", "女 · 柔和知性，有声书"), v("baiqinglan_v3.1", "白清岚", "女 · 明亮清纯"),
        v("xuyuyuan_v3.1", "许玉远", "女 · 知性成熟，新闻、旁白"), v("anruorou_v3.1", "安若柔", "女 · 气声知性"),
        v("wenhuaizhi_v3.1", "闻怀之", "女 · 稳重成熟，有声书"), v("xiaoxingzhi_v3.1", "萧行之", "女 · 端庄贵气"),
        v("guyunshu_v3.1", "顾云舒", "女 · 成熟稳重，电台"), v("huozhuoshi_v3.1", "霍拙石", "男 · 清亮，有声书"),
        v("yeqinghe_v3.1", "叶清禾", "女 · 亲切温柔，有声书"), v("yunhuanhuan_v3.1", "云欢欢", "女 · 高亢热情"),
        v("xuxiaoqiao_v3.1", "徐小俏", "女 · 自然俏皮"), v("baianran_v3.1", "白安然", "女 · 低沉浑厚气声，配音讲解"),
        v("xuyanchu_v3.1", "许言初", "女 · 沉稳磁性，新闻播报"), v("yezhiqing_v3.1", "叶知晴", "女 · 轻快自然，儿童故事"),
        v("andi_v3.1", "安迪", "男 · ABC 口音"), v("anyuqing_v3.1", "安语晴", "女 · 甜妹"),
        v("Emily_v3.1", "Emily", "女 · 英式"), v("Luna_v3.1", "Luna", "女 · 英式"),
        v("Eric_v3.1", "Eric", "男 · 英式"), v("Luca_v3.1", "Luca", "男 · 英式"),
        v("Abby_v3.1", "Abby", "女 · 美式"), v("Annie_v3.1", "Annie", "女 · 美式"),
        v("Ava_v3.1", "Ava", "女 · 美式"), v("Beth_v3.1", "Beth", "女 · 美式"),
        v("Betty_v3.1", "Betty", "女 · 美式"), v("Cally_v3.1", "Cally", "女 · 美式"),
        v("Cindy_v3.1", "Cindy", "女 · 美式"), v("Donna_v3.1", "Donna", "女 · 美式"),
        v("Andy_v3.1", "Andy", "男 · 美式"), v("Brian_v3.1", "Brian", "男 · 美式"), v("David_v3.1", "David", "男 · 美式"),
        v("longanyuanfei_v3.1", "龙安元妃", "女 · 高傲妃子音"), v("longjielidou_v3.1", "龙杰力豆", "男 · 天真男童"),
        v("longanlingxi_v3.1", "龙安灵希", "女 · 可爱甜美"), v("longhuohuo_v3.1", "龙火火", "男 · 顽皮少年"),
        v("longyingtao_v3.1", "龙应桃", "女 · 温柔淡定，客服"), v("longanya_v3.1", "龙安雅", "女 · 高雅气质"),
        v("longwan_v3.1", "龙婉", "女 · 细腻柔声"), v("longxing_v3.1", "龙星", "女 · 温婉邻家"),
        v("longhua_v3.1", "龙华", "女 · 元气甜美"), v("longhan_v3.1", "龙寒", "男 · 温暖痴情"),
        v("longanzhi_v3.1", "龙安智", "男 · 睿智轻熟"), v("longzhe_v3.1", "龙哲", "男 · 呆板大暖男"),
        v("longanyang_v3.1", "龙安洋", "男 · 阳光大男孩"), v("libai_v3.1", "李白", "男 · 诗仙，诗词朗诵"),
        v("longling_v3.1", "龙铃", "女 · 稚气童声"), v("longniuniu_v3.1", "龙牛牛", "男 · 阳光男童"),
        v("longshanshan_v3.1", "龙闪闪", "男 · 戏剧化童声"), v("longpaopao_v3.1", "龙泡泡", "女 · 飞天泡泡音"),
        v("loongstella_v3.1", "loongstella", "女 · 飒爽利落，新闻"), v("longyuan_v3.1", "龙媛", "女 · 温暖治愈，有声书"),
        v("longmiao_v3.1", "龙妙", "女 · 抑扬顿挫，有声书"), v("longsanshu_v3.1", "龙三叔", "男 · 沉稳质感，有声书"),
        v("longanli_v3.1", "龙安莉", "女 · 利落从容，语音助手"), v("longanwen_v3.1", "龙安温", "女 · 优雅知性"),
        v("longanlang_v3.1", "龙安朗", "男 · 清爽利落"), v("longxiaoxia_v3.1", "龙小夏", "女 · 沉稳权威"),
        v("longanchong_v3.1", "龙安冲", "男 · 激情推销，直播带货"),
    )

    /** qwen-audio-3.0-tts-flash. */
    val audio30Flash = listOf(
        v("longanhuan_v3.6", "龙安欢", "女 · 社交陪伴"), v("longanfengyue", "龙安风悦", "女 · 自然亲切"),
        v("longanyuanfei", "龙安元妃", "女 · 高傲妃子音"), v("longanlingxi", "龙安灵希", "女 · 可爱甜美"),
        v("longanxiaoxin", "龙安小昕", "女 · 亲切活泼"), v("longjielidou_v3.6", "龙杰力豆", "男 · 天真男童"),
        v("longpaopao_v3.6", "龙泡泡", "女 · 软糯可爱"), v("longhuohuo_v3.6", "龙火火", "男 · 顽皮少年"),
        v("longchuanshu_v3.6", "龙川叔", "男 · 川普大叔"), v("loongmary", "Mary", "女 · 温暖英音（仅英文）"),
        v("loongeva_v3.6", "Eva", "女 · 高智美音（仅英文）"), v("loongjohn", "John", "男 · 沉稳亲切美音（仅英文）"),
    )

    /** qwen-audio-3.0-tts-plus. */
    val audio30Plus = listOf(
        v("longanlingxin", "龙安灵心", "女 · 知心温暖，旗舰音色"), v("longanlufeng", "龙安鲁风", "男 · 明亮开朗，旗舰音色"),
    )

    /** Voices work only with the model they are listed under, so the list follows the model. */
    fun of(api: TtsApi, model: String = api.defaultModel): List<TtsVoice> = when {
        api == TtsApi.Qwen3Flash -> qwen3Flash
        model.contains("3.1") -> audio31
        model.contains("plus") -> audio30Plus
        else -> audio30Flash
    }
}

/** Estimated spend from the characters this phone has had synthesized (the service has no key-based balance query). */
object TtsCost {
    const val DEFAULT_UNIT_PRICE = 0.8f

    fun spent(usedChars: Long, unitPrice: Float): Double = usedChars / 10_000.0 * unitPrice

    /** Yuan left from [balance], or null when no balance was set. */
    fun remaining(balance: Float, usedChars: Long, unitPrice: Float): Double? =
        if (balance > 0f) balance - spent(usedChars, unitPrice) else null

    /** How many more characters the remaining amount buys. */
    fun charactersLeft(remaining: Double, unitPrice: Float): Long =
        if (unitPrice > 0f && remaining > 0) Math.round(remaining / unitPrice * 10_000) else 0L

    fun yuan(value: Double): String = (if (value < 0) "-¥" else "¥") + String.format(java.util.Locale.ROOT, "%.2f", kotlin.math.abs(value))
}
