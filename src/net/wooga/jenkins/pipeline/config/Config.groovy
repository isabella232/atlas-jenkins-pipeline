package net.wooga.jenkins.pipeline.config

class Config {

    final Platform[] platforms
    final JenkinsMetadata metadata
    final DockerArgs dockerArgs
    final SonarQubeArgs sonarArgs
    final String coverallsToken

    static Config fromConfigMap(Map config,  Object jenkinsScript) {
        config.platforms = config.platforms ?: ['macos','windows']
        def platforms = config.platforms.collect { String platformName ->
            Platform.forJava(platformName, config)
        }
        def dockerArgs = DockerArgs.fromConfigMap((config.dockerArgs?: [:]) as Map)
        def sonarArgs = SonarQubeArgs.fromConfigMap(config)
        def coverallsToken = config.coverallsToken as String
        def metadata = JenkinsMetadata.fromScript(jenkinsScript)
        return new Config(metadata, platforms, dockerArgs, sonarArgs, coverallsToken)
    }

    Config(JenkinsMetadata metadata, List<Platform> platforms, DockerArgs dockerArgs, SonarQubeArgs sonarArgs,
           String coverallsToken) {
        this.metadata = metadata
        this.platforms = platforms
        this.sonarArgs = sonarArgs
        this.dockerArgs = dockerArgs
        this.coverallsToken = coverallsToken
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Config config = (Config) o

        if (coverallsToken != config.coverallsToken) return false
        if (dockerArgs != config.dockerArgs) return false
        if (metadata != config.metadata) return false
        if (!Arrays.equals(platforms, config.platforms)) return false
        if (sonarArgs != config.sonarArgs) return false

        return true
    }

    int hashCode() {
        int result
        result = (platforms != null ? Arrays.hashCode(platforms) : 0)
        result = 31 * result + (metadata != null ? metadata.hashCode() : 0)
        result = 31 * result + (dockerArgs != null ? dockerArgs.hashCode() : 0)
        result = 31 * result + (sonarArgs != null ? sonarArgs.hashCode() : 0)
        result = 31 * result + (coverallsToken != null ? coverallsToken.hashCode() : 0)
        return result
    }
}