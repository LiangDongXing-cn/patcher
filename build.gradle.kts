plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.bit"
version = "2024.4"

repositories {
    mavenCentral()

    intellijPlatform {
        releases()
        marketplace()
        defaultRepositories()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    patchPluginXml {
        // https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
        sinceBuild.set("243")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    runIde {
        jvmArgs()
    }
}


dependencies {
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    intellijPlatform {
        intellijIdeaUltimate("2024.3")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}