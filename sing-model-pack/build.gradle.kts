// Sing's LiteRT builds of Lyra-Sing-Lite as a Play AI Pack (Play for On-device AI): on demand, split by the
// device groups in app/device_targeting_configuration.xml. Each model#group_<group>/ holds that group's build(s)
// and the models.json meta naming them; Play strips the #group_ suffix, so the app reads model/ (player/sing/
// SingModelSources.kt). The files are the official ones from lyra-sing-lite artifacts/tflite/official_ai_pack, plus
// the fp16 build (lyra_sing_lite_fp16.tflite) in the Tensor G6/G5 groups: the GPU/CPU fallback when the NPU fails.
plugins {
    alias(libs.plugins.android.ai.pack)
}

aiPack {
    packName = "sing_model_pack"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}
