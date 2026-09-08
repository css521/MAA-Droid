plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("assetManifest") {
            id = "com.aliothmoon.maadroid.asset-manifest"
            implementationClass = "com.aliothmoon.maadroid.buildlogic.AssetManifestPlugin"
        }
        register("i18nVerify") {
            id = "com.aliothmoon.maadroid.i18n-verify"
            implementationClass = "com.aliothmoon.maadroid.buildlogic.I18nVerifyPlugin"
        }
    }
}
