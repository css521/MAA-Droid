plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("assetManifest") {
            id = "com.maadroid.app.asset-manifest"
            implementationClass = "com.maadroid.app.buildlogic.AssetManifestPlugin"
        }
        register("i18nVerify") {
            id = "com.maadroid.app.i18n-verify"
            implementationClass = "com.maadroid.app.buildlogic.I18nVerifyPlugin"
        }
    }
}
