package scripts

import com.lesfurets.jenkins.unit.MethodCall
import net.wooga.jenkins.pipeline.config.Config
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.config.UnityVersionPlatform
import net.wooga.jenkins.pipeline.config.WDKConfig
import spock.lang.Unroll
import tools.DeclarativeJenkinsSpec

class WDKCheckSpec extends DeclarativeJenkinsSpec {
    private static final String TEST_SCRIPT_PATH = "test/resources/scripts/checkTest.groovy"

    def "executes cobertura coverage plugin"() {
        given: "loaded check in a running build"
        def check = loadScript(TEST_SCRIPT_PATH)
        and: "configuration in the master branch and with tokens"
        def config = WDKConfig.fromConfigMap("label",
                [unityVersions: "2019"],
                [BUILD_NUMBER: 1, BRANCH_NAME: "branch"]
        )
        and: "mocked coverage methods"
        helper.registerAllowedMethod("istanbulCoberturaAdapter", [String]) { it -> it }
        helper.registerAllowedMethod("sourceFiles", [String]) { it -> it }
        helper.registerAllowedMethod("publishCoverage", [Map]) { it -> it }
        and: "stashed setup data"
        def stashKey = "setup_w"
        jenkinsStash[stashKey] = [useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"]

        when: "running gradle pipeline with coverage token"
        check().wdkCoverage(config, "any", "any", stashKey).each { it.value() }

        then: "jenkins coverage plugins are called"
        calls.has["istanbulCoberturaAdapter"] { MethodCall call ->
            call.args.length == 1 && call.args[0] == '**/codeCoverage/Cobertura.xml'
        }
        calls.has["sourceFiles"] { MethodCall call ->
            call.args.length == 1 && call.args[0] == 'STORE_LAST_BUILD'
        }
        calls.has["publishCoverage"] { MethodCall call ->
            call.args.length == 1 &&
                    call.args[0]["adapters"] == ["**/codeCoverage/Cobertura.xml"] &&
                    call.args[0]["sourceFileResolver"] == "STORE_LAST_BUILD"
        }
    }

    @Unroll("execute sonarqube when its token is present")
    def "executes sonarqube when its token is present"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)

        and: "configuration object with given platforms"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: "2019", sonarToken: sonarToken], [BUILD_NUMBER: 1])

        and: "stashed setup data"
        def stashKey = "setup_w"
        jenkinsStash[stashKey] = [useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"]

        when: "running check with sonarqube token"
        def checkSteps = check().wdkCoverage(config, "any", "any", stashKey) as Map<String, Closure>
        checkSteps.each { it.value.call() }

        then: "gradle coverage task is called"
        gradleCmdElements.every { it ->
            it.every { element ->
                calls.has["sh"] { MethodCall call ->
                    String args = call.args[0]["script"]
                    args.contains("gradlew") && args.contains("sonarqube") && args.contains(element)
                }
            }
        }

        where:
        name        | gradleCmdElements              | sonarToken
        "SonarQube" | [["-Dsonar.login=sonarToken"]] | "sonarToken"
    }

    @Unroll("does not executes sonarqube when its token is present")
    def "does not executes sonarqube when its token is not present"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)

        and: "configuration object with given platforms"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: "2019", sonarToken: "any"], [BUILD_NUMBER: 1])

        and: "stashed setup data"
        def stashKey = "setup_w"
        jenkinsStash[stashKey] = [useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"]

        when: "running check with sonarqube token"
        def checkSteps = check().wdkCoverage(config, "any", "any", stashKey) as Map<String, Closure>
        checkSteps.each { it.value.call() }

        then: "gradle coverage task is called"
        calls["sh"].
                collect { MethodCall call -> call.args[0]["script"] as String}.
                find {String args -> args.contains("gradlew")}.
                every {String args -> !args.contains("sonarqube")}
    }

    @Unroll("executes sonarqube on branch #branchName")
    def "executes sonarqube in PR and non-PR branches with correct arguments"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)

        and: "jenkins script object with needed properties"
        def jenkinsMeta = [BUILD_NUMBER: 1, BRANCH_NAME: branchName, env: [:]]
        if (isPR) {
            jenkinsMeta.env["CHANGE_ID"] = "notnull"
        }
        and: "configuration in the ${branchName} branch with token"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: "2019", sonarToken: "sonarToken"], jenkinsMeta)

        and: "stashed setup data"
        def stashKey = "setup_w"
        jenkinsStash[stashKey] = [useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"]

        when: "running check with sonarqube token"
        def checkSteps = check().wdkCoverage(config, "any", "any", stashKey) as Map<String, Closure>
        checkSteps.each { it.value.call() }

        then: "should run sonar analysis"
        calls.has["sh"] { MethodCall call ->
            String callString = call.args[0]["script"]
            callString.contains("gradlew") &&
                    callString.contains("sonarqube") &&
                    callString.contains("-Dsonar.login=sonarToken") &&
                    callString.contains("-Pgithub.branch.name=${expectedBranchProperty}")
        }

        where:
        branchName   | prBranch | isPR  | expectedBranchProperty
        "master"     | null     | false | "master"
        "not_master" | null     | false | "not_master"
        "PR-123"     | "branch" | true  | ""
        "branchpr"   | "branch" | true  | ""
    }

    @Unroll("runs check step for unity versions #versions")
    def "runs check step for all given versions"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: versions], [BUILD_NUMBER: 1])
        and: "stashed setup data"
        jenkinsStash[setupStashId] = [useDefaultExcludes: true, includes: "paket.lock .gradle/**, **/build/**, .paket/**, packages/**, paket-files/**, **/Paket.Unity3D/**, **/Wooga/Plugins/**"]


        when: "running check"
        def checkSteps = check().wdkCoverage(config, releaseType, releaseScope, setupStashId) as Map<String, Closure>
        checkSteps.each { it.value.call() }

        then: "platform check registered on parallel operation"
        checkSteps.collect {
            it -> it.key.replace("check Unity-", "").trim()
        } == versions

        and: "working dir changed to version directory"
        calls["dir"].length == versions.size()
        calls["dir"].eachWithIndex { it, i -> it.args[0] == versions[i] }

        and: "code checkouted, setup unstashed"
        calls["checkout"].length == versions.size()
        calls["unstash"].count { it.args[0] == setupStashId } == versions.size()

        and: "gradle check was called"
        calls["sh"].count {
            String call = it.args[0]["script"]
            return call.contains("gradlew") &&
                    call.contains("-Prelease.stage=${releaseType.trim()}") &&
                    call.contains("-Prelease.scope=${releaseScope.trim()}") &&
                    call.contains("check")
        } == versions.size()

        and: "nunit results are stored"
        calls["nunit"].count {
            Map args = it.args[0] as Map
            return args["failIfNoResults"] == false &&
                    args["testResultsPattern"] == '**/build/reports/unity/test*/*.xml'
        } == versions.size()
        calls["cleanWs"].length == versions.size()

        where:
        versions         | releaseType | releaseScope | setupStashId
        ["2019"]         | "type"      | "scope"      | "setup_w"
        ["2019", "2020"] | "other "    | "others"     | "stash"
    }

    @Unroll("executes finally steps on check #throwsException for #versions")
    def "always executes finally steps on check run"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: versions], [BUILD_NUMBER: 1])
        and: "some failing script step"
        helper.registerAllowedMethod("dir", [String, Closure]) {
            if (throwsException == "throwing") throw new InputMismatchException()
        }

        when: "running check"
        def checkSteps = check().wdkCoverage(config, "any", "any", "setup_w") as Map<String, Closure>
        checkSteps.each {
            try {
                it.value.call()
            } catch (InputMismatchException _) {
            } //ignores exception, it is tested in another test
        }

        then: "nunit results are stored"
        calls["nunit"].count {
            Map args = it.args[0] as Map
            return args["failIfNoResults"] == false &&
                    args["testResultsPattern"] == '**/build/reports/unity/test*/*.xml'
        } == versions.size()
        calls["cleanWs"].length == versions.size()

        where:
        versions                                    | throwsException
        ["2018"]                                    | "throwing"
        ["2018"]                                    | "not throwing"
        [[version: "2018", optional: true]]         | "throwing"
        [[version: "2018", optional: true]]         | "not throwing"
        ["2018", "2019"]                            | "throwing"
        ["2018", "2019"]                            | "not throwing"
        [[version: "2018", optional: true], "2019"] | "throwing"
        [[version: "2018", optional: true], "2019"] | "not throwing"
    }

    def "optional version does not throw Exception on failure"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: [version]], [BUILD_NUMBER: 1])
        and: "some failing script step"
        helper.registerAllowedMethod("dir", [String, Closure]) { throw new Exception() }
        when: "running check"
        def checkSteps = check().wdkCoverage(config, "any", "any", "setup_w") as Map<String, Closure>
        checkSteps.each { it.value.call() }

        then:
        noExceptionThrown()
        calls.has["unstable"] { MethodCall it ->
            String message = it.args[0]["message"]
            message.startsWith("Unity build for optional version ${version.version} is found to be unstable")
        }
        where:
        version << [[version: "2019", optional: true]]
    }


    def "non-optional version throws Exception on failure"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH)
        and: "configuration object with given platforms"
        def config = WDKConfig.fromConfigMap("label", [unityVersions: [version]], [BUILD_NUMBER: 1])
        and: "some failing script step"
        def expectedException = new InstantiationException() //some uncommon exception type
        helper.registerAllowedMethod("dir", [String, Closure]) { throw expectedException }
        when: "running check"
        def checkSteps = check().wdkCoverage(config, "any", "any", "setup_w") as Map<String, Closure>
        checkSteps.each { it.value.call() }

        then:
        def e = thrown(InstantiationException)
        e == expectedException

        where:
        version << [[version: "2019"]]
    }

    @Unroll
    def "loads test environment on check"() {
        given: "loaded check in a running jenkins build"
        def check = loadScript(TEST_SCRIPT_PATH) {}
        and: "configuration object with more than one platform"
        def config = WDKConfig.fromConfigMap("label",
                [unityVersions: versions, testEnvironment: testEnvironment], [BUILD_NUMBER: 1])
        and: "generated check steps"
        Map<String, Map> checkEnvMap = versions.collectEntries { [(it): [:]] }
        Map<String, Map> analysisEnvMap = versions.collectEntries { [(it): [:]] }
        Map<String, Closure> steps = check().simple(config,
                { UnityVersionPlatform uPlat ->
                    binding.env.every { checkEnvMap[uPlat.platform.name][it.key] = it.value }
                },
                { UnityVersionPlatform uPlat ->
                    binding.env.every { analysisEnvMap[uPlat.platform.name][it.key] = it.value }
                }
        )

        when: "running steps"
        steps.each { it.value.call() }

        then: "test step ran for all platforms"
        checkEnvMap.every { platEnv ->
            platEnv.value["TRAVIS_JOB_NUMBER"] == "1.${platEnv.key.toUpperCase()}" &&
                    platEnv.value["UVM_UNITY_VERSION"] == platEnv.key &&
                    platEnv.value["UNITY_LOG_CATEGORY"] == "check-${platEnv.key}"
        }
        expectedInEnvironment.every { expPlatEnv ->
            def actualPlatEnv = checkEnvMap[expPlatEnv.key]
            actualPlatEnv.entrySet().containsAll(expPlatEnv.value.entrySet())
        }
        //analysis only runs once, so we only assert for first platform
        def firstPlat = versions[0]
        def actualAnalysisPlatEnv = analysisEnvMap[firstPlat]
        actualAnalysisPlatEnv.entrySet().containsAll(expectedInEnvironment[firstPlat].entrySet())

        where:
        versions         | testEnvironment          | expectedInEnvironment
        ["2019"]         | ["a=b", "c=d"]           | ["2019": [a: "b", c: "d"]]
        ["2019"]         | ["2019": ["a=b", "c=d"]] | ["2019": [a: "b", c: "d"]]
        ["2019", "2020"] | ["a=b", "c=d"]           | ["2019": [a: "b", c: "d"], "2020": [a: "b", c: "d"]]
        ["2019", "2020"] | ["2020": ["a=b", "c=d"]] | ["2019": [:], "2020": [a: "b", c: "d"]]
    }
}
