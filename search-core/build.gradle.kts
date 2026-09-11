plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

dependencies {
    testImplementation(libs.junit)
}

/**
 * The ryeong production search evaluation of record (v3).
 *
 * Registered but not wired into `test`, `check` or `build`. An evaluation whose result is quoted
 * afterwards should be something a person asked for by name, not something that happens because
 * unit tests ran — and the last runner wrote into a frozen evidence directory every time the suite
 * executed. The runner refuses an output path that already exists and refuses the run_1/run_2
 * directories outright; the defaults below point at the frozen run_3 input and a result directory
 * that does not exist yet.
 */
tasks.register<JavaExec>("runRyeongSearchEvalV3") {
    group = "verification"
    description = "Runs the frozen ryeong dataset through the production search path against the v3 gate."
    mainClass.set("com.hjp.searchlookup.eval.RyeongProductionSearchEvalV3Runner")
    classpath = sourceSets["test"].runtimeClasspath
    val input = providers.gradleProperty("ryeongEvalV3Input")
        .getOrElse("../integration_evidence/production_eval/run_3/input")
    val gate = providers.gradleProperty("ryeongEvalV3Gate")
        .getOrElse("../integration_evidence/production_eval/run_3/gate/ryeong_search_gate_v3.json")
    val output = providers.gradleProperty("ryeongEvalV3Output")
        .getOrElse("../integration_evidence/production_eval/run_3/result")
    args("--input", input, "--gate", gate, "--output", output)
}
