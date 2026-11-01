plugins {
    java
}

val teamcityVersion = "2026.2"

repositories {
    mavenCentral()
    maven("https://download.jetbrains.com/teamcity-repository")
}

dependencies {
    compileOnly("org.jetbrains.teamcity:server-api:$teamcityVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.release = 17
}

tasks.test {
    useJUnitPlatform()
}

val pluginZip by tasks.registering(Zip::class) {
    archiveFileName = "fair-share.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from("teamcity-plugin.xml")
    into("server") { from(tasks.jar) }
}

tasks.assemble {
    dependsOn(pluginZip)
}
