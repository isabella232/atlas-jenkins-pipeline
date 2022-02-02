import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.config.JavaConfig


def call(Map configMap, Closure overrides = {}) {
    def config = configMap.config as JavaConfig

    withEnv(["COVERALLS_PARALLEL=true"]) {
        def checks = Checks.forJavaPipelines(this, config)
        def checksForParallel = checks.javaCoverage(config, overrides)
        parallel checksForParallel
    }
}
