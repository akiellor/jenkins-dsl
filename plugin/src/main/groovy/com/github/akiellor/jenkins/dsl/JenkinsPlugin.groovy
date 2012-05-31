package com.github.akiellor.jenkins.dsl

import groovy.xml.MarkupBuilder
import org.gradle.api.Plugin
import org.gradle.api.Project

class ShellBuilder {
    def command

    public ShellBuilder(command) {
        this.command = command;
    }

    def apply(builder) {
        builder.'hudson.tasks.Shell' {
            builder.command this.command
        }
    }
}

class NullScm {
    def apply(builder) {
        builder.scm(class: "hudson.scm.NullSCM")
    }
}

class SubversionScm {
    def url

    public SubversionScm(url) {
        this.url = url;
    }

    def apply(builder) {
        builder.scm(class: "hudson.scm.SubversionSCM") {
            locations {
                'hudson.scm.SubversionSCM_-ModuleLocation' {
                    remote(this.url)
                    local('.')
                }
            }
            excludedRegions()
            includedRegions()
            excludedUsers()
            excludedRevprop()
            excludedCommitMessages()
            workspaceUpdater(class: "hudson.scm.subversion.CheckoutUpdater")
        }
    }
}

class ScmTrigger {
    def schedule

    public ScmTrigger(schedule) {
        this.schedule = schedule;
    }

    def apply(builder) {
        builder.'hudson.triggers.SCMTrigger' {
            spec(schedule)
        }
    }
}

class StringBuildParameter {
    String name
    String defaultValue
    String description

    public StringBuildParameter(String name, String defaultValue, String description) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public StringBuildParameter(String name) {
        this(name, '', '');
    }

    def apply(builder) {
        builder.'hudson.model.ParametersDefinitionProperty' {
            parameterDefinitions {
                builder.'hudson.model.StringParameterDefinition' {
                    builder.name(name)
                    builder.description(description)
                    builder.defaultValue(defaultValue)
                }
            }
        }
    }
}

class Job {
    def artifacts = []
    def builders = [];
    def triggers = [];
    def scm = new NullScm();
    def publishers = [];
    def workspace;
    public String name
    def parameters = []
    def raws = []

    public Job(String name, Closure config) {
        this.name = name
        config.setDelegate(this);
        config.call()
    }

    def sh(command) {
        builders << new ShellBuilder(command)
    }

    def artifacts(paths) {
        artifacts.add(paths)
    }

    def subversion(url) {
        scm = new SubversionScm(url)
        triggers << new ScmTrigger('0 * * * */20')
    }

    def parameterisedTrigger(stageName, parameters) {
        publishers << new ParameterisedTrigger(stageName, parameters)
    }

    def workspace(dir) {
        this.workspace = dir;
    }

    def parameter(parameterName) {
        parameters << new StringBuildParameter(parameterName)
    }

    def String toString() {
        StringWriter writer = new StringWriter()
        MarkupBuilder builder = new MarkupBuilder(writer);

        builder.project {
            builder.properties {
                parameters.each {
                    it.apply(builder)
                }
            }
            if (workspace != null) {
                builder.customWorkspace(workspace)
            }

            builder.publishers {
                artifacts.each { paths ->
                    'hudson.tasks.ArtifactArchiver' {
                        builder.artifacts(paths)
                        latestOnly(false)
                    }
                }

                publishers.each { it.apply builder }
            }

            scm.apply(builder)

            builder.triggers(class: 'vector') {
                triggers.each {
                    it.apply(builder)
                }
            }

            builder.builders {
                builders.each {
                    it.apply builder
                }
            }

            actions()
            description()
            keepDependencies(false)
            canRoam(true)
            disabled(false)
            blockBuildWhenDownstreamBuilding(false)
            blockBuildWhenUpstreamBuilding(false)
            concurrentBuild(false)

            raws.each {
                it.delegate = builder
                it.call()
            }
        }

        return writer.toString();
    }

    def raw(closure) {
        raws << closure
    }
}

class Jobs {
    private jobs = new HashMap();

    public Jobs(Closure config) {
        config.setDelegate(this)
        config.call()
    }

    def job(name, config) {
        jobs[name] = new Job(name, config);
    }

    def dump() {
        return jobs.toString();
    }

    def eachStage(closure) {
        jobs.each(closure)
    }
}

class Jenkins {
    def server;
    def jar

    public Jenkins(server, jar) {
        this.server = server;
        this.jar = jar
    }

    private hasStage(stageName) {
        def proc = cli("get-job $stageName")
        proc.waitFor();
        proc.exitValue() == 0
    }

    private create(stageName, stage) {
        def proc = cli("create-job $stageName")
        proc.withWriter { w ->
            w << stage.toString()
        }
        proc.waitFor();
        proc.consumeProcessOutput(System.out, System.err)
    }

    private update(stageName, stage) {
        def proc = cli("update-job $stageName")
        proc.withWriter { w ->
            w << stage.toString()
        }
        proc.waitFor();
        proc.consumeProcessOutput(System.out, System.err)
    }

    private cli(command){
        "java -jar $jar -s $server $command".execute()
    }

    def publish(Jobs pipeline) {
        pipeline.eachStage({name, stage ->
            if (hasStage(stage.name)) {
                update(stage.name, stage)
            } else {
                create(stage.name, stage)
            }
        })
    }
}

class ParameterisedTrigger {
    def properties
    def stageName

    public ParameterisedTrigger(stageName, properties) {
        this.stageName = stageName
        this.properties = properties
    }

    def apply(builder) {
        builder.'hudson.plugins.parameterizedtrigger.BuildTrigger' {
            configs {
                builder.'hudson.plugins.parameterizedtrigger.BuildTriggerConfig' {
                    configs {
                        builder.'hudson.plugins.parameterizedtrigger.PredefinedBuildParameters' {
                            builder.properties(properties)
                        }
                    }
                    projects(stageName)
                    condition('SUCCESS')
                    triggerWithNoParameters(false)
                }
            }
        }
    }
}

class JenkinsExtension {
    String server
    Closure config

    def jobs(closure){
        config = closure
    }
}

class JenkinsPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.buildscript.configurations.add('jenkinsCli')

        project.buildscript.dependencies {
            jenkinsCli name: 'jenkins-cli'
        }

        project.extensions.create("jenkins", JenkinsExtension)

        project.task('jobs') << {
            println new Jobs(project.jenkins.config).dump()
        }

        def cliJar = project.buildscript.configurations.jenkinsCli.files.iterator().next()

        project.task('jenkins') << {
            println project.jenkins.server
            println cliJar
        }

        project.task('publishJobs') << {
            new Jenkins(project.jenkins.server, cliJar).publish(new Jobs(project.jenkins.config))
        }
    }
}

