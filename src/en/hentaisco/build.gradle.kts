import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiSco"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://hentaisco.cc"
    }

    deeplink {
        path("/hentai/..*")
    }
}

dependencies {
    implementation(project(":lib:randomua"))
}
