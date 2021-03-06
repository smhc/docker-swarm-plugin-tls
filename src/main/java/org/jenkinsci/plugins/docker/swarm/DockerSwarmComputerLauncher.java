package org.jenkinsci.plugins.docker.swarm;


import akka.actor.ActorRef;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import jenkins.slaves.RemotingWorkDirSettings;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.swarm.docker.api.configs.Config;
import org.jenkinsci.plugins.docker.swarm.docker.api.configs.ListConfigsRequest;
import org.jenkinsci.plugins.docker.swarm.docker.api.response.ApiException;
import org.jenkinsci.plugins.docker.swarm.docker.api.response.SerializationException;
import org.jenkinsci.plugins.docker.swarm.docker.api.secrets.ListSecretsRequest;
import org.jenkinsci.plugins.docker.swarm.docker.api.secrets.Secret;
import org.jenkinsci.plugins.docker.swarm.docker.api.service.ServiceSpec;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

public class DockerSwarmComputerLauncher extends JNLPLauncher {

    private final String label;
    private final String jobName;
    private final Queue.BuildableItem bi;
    private DockerSwarmAgentInfo agentInfo;

    private static final Logger LOGGER = Logger.getLogger(DockerSwarmComputerLauncher.class.getName());

    public DockerSwarmComputerLauncher(final Queue.BuildableItem bi) {
        super(
                DockerSwarmCloud.get().getTunnel(),
                null,
                new RemotingWorkDirSettings(
                        false,
                        "/tmp",
                        null,
                        false
                )
        );
        this.bi = bi;
        this.label = bi.task.getAssignedLabel().getName();
        this.jobName = bi.task instanceof AbstractProject ? ((AbstractProject) bi.task).getFullName() : bi.task.getName();
    }

    @Override
    public void launch(final SlaveComputer computer, final TaskListener listener) {
        if (computer instanceof DockerSwarmComputer) {
            try {
                launch((DockerSwarmComputer) computer, listener);
            }
            catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to launch", e);
            }
        } else {
            throw new IllegalArgumentException("This launcher only can handle DockerSwarmComputer");
        }
    }

    private void launch(final DockerSwarmComputer computer, final TaskListener listener) throws IOException {
        final DockerSwarmCloud configuration = DockerSwarmCloud.get();
        final DockerSwarmAgentTemplate dockerSwarmAgentTemplate = configuration.getLabelConfiguration(this.label);

        this.agentInfo = this.bi.getAction(DockerSwarmAgentInfo.class);
        this.agentInfo.setDockerImage(dockerSwarmAgentTemplate.getImage());
        this.agentInfo.setLimitsNanoCPUs(dockerSwarmAgentTemplate.getLimitsNanoCPUs());
        this.agentInfo.setReservationsMemoryBytes(dockerSwarmAgentTemplate.getReservationsMemoryBytes());
        this.agentInfo.setReservationsNanoCPUs(dockerSwarmAgentTemplate.getReservationsNanoCPUs());

        setBaseWorkspaceLocation(dockerSwarmAgentTemplate);

        final String[] envVarOptions = dockerSwarmAgentTemplate.getEnvVarsConfig();
        final String[] envVars = new String[envVarOptions.length + 3];
        if (envVarOptions.length != 0) {
            System.arraycopy(envVarOptions, 0, envVars, 0, envVarOptions.length);
        }
        envVars[envVarOptions.length]   = "DOCKER_SWARM_PLUGIN_JENKINS_AGENT_SECRET=" + getAgentSecret(computer);
        envVars[envVarOptions.length+1] = "DOCKER_SWARM_PLUGIN_JENKINS_AGENT_JAR_URL=" + getAgentJarUrl(configuration);
        envVars[envVarOptions.length+2] = "DOCKER_SWARM_PLUGIN_JENKINS_AGENT_JNLP_URL=" + getAgentJnlpUrl(computer, configuration);

        if (dockerSwarmAgentTemplate.isOsWindows()) {
            // On windows use hard-coded command. TODO: Use configured command if configured.
            final String agentOptions = String.join(" ", "-jnlpUrl", getAgentJnlpUrl(computer, configuration), "-secret", getAgentSecret(computer), "-noReconnect");
            String interpreter;
            String interpreterOptions;
            String fetchAndLaunchCommand;
            interpreter = "powershell.exe";
            interpreterOptions = "";
            fetchAndLaunchCommand = "& { Invoke-WebRequest -TimeoutSec 20 -OutFile slave.jar " + getAgentJarUrl(configuration) + "; if($?) { java -jar slave.jar " + agentOptions + " } }";
            final String[] command = new String[]{interpreter, interpreterOptions, fetchAndLaunchCommand};
            launchContainer(command,configuration, envVars, dockerSwarmAgentTemplate.getWorkingDir(), 
                    dockerSwarmAgentTemplate.getUser(), dockerSwarmAgentTemplate, listener, computer);
        }
        else {
            launchContainer(dockerSwarmAgentTemplate.getCommandConfig(),configuration, envVars,
                   dockerSwarmAgentTemplate.getWorkingDir(),  dockerSwarmAgentTemplate.getUser(), dockerSwarmAgentTemplate, listener, computer);
        }
    }

    private void setBaseWorkspaceLocation(DockerSwarmAgentTemplate dockerSwarmAgentTemplate){
        if (this.bi.task instanceof AbstractProject && StringUtils.isNotEmpty(dockerSwarmAgentTemplate.getBaseWorkspaceLocation())) {
            try {
                ((AbstractProject) this.bi.task).setCustomWorkspace(dockerSwarmAgentTemplate.getBaseWorkspaceLocation());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void launchContainer(String[] commands, DockerSwarmCloud configuration, String[] envVars, String dir, String user,
                                DockerSwarmAgentTemplate dockerSwarmAgentTemplate, TaskListener listener, DockerSwarmComputer computer) throws IOException {
        DockerSwarmPlugin swarmPlugin = Jenkins.getInstance().getPlugin(DockerSwarmPlugin.class);
        ServiceSpec crReq = createCreateServiceRequest(commands, configuration, envVars, dir, user, dockerSwarmAgentTemplate, computer);

        setLimitsAndReservations(dockerSwarmAgentTemplate, crReq);
        setHostBinds(dockerSwarmAgentTemplate, crReq);
        setSecrets(dockerSwarmAgentTemplate, crReq);
        setConfigs(dockerSwarmAgentTemplate, crReq);
        setNetwork(configuration, crReq);
        setCacheDirs(configuration, dockerSwarmAgentTemplate, listener, computer, crReq);
        setTmpfs(dockerSwarmAgentTemplate, crReq);
        setConstraints(dockerSwarmAgentTemplate,crReq);
        setLabels(crReq);
        setRestartAttemptCount(crReq);

        this.agentInfo.setServiceRequestJson(crReq.toJsonString());

        final ActorRef agentLauncher = swarmPlugin.getActorSystem().actorOf(DockerSwarmAgentLauncherActor.props(listener.getLogger()), computer.getName());
        agentLauncher.tell(crReq,ActorRef.noSender());
    }

    private void setRestartAttemptCount(ServiceSpec crReq) {
        crReq.TaskTemplate.setRestartAttemptCount(0);
    }

    private void setLabels(ServiceSpec crReq) {
        crReq.addLabel("ROLE","jenkins-agent");
    }

    private void setConstraints(DockerSwarmAgentTemplate dockerSwarmAgentTemplate, ServiceSpec crReq) {
        crReq.TaskTemplate.setPlacementConstraints(dockerSwarmAgentTemplate.getPlacementConstraintsConfig());
    }

    private ServiceSpec createCreateServiceRequest(String[] commands, DockerSwarmCloud configuration, String[] envVars, String dir, String user,
                                                   DockerSwarmAgentTemplate dockerSwarmAgentTemplate, DockerSwarmComputer computer) throws IOException {
        ServiceSpec crReq;
        if(dockerSwarmAgentTemplate.getLabel().contains("dind")){
            commands[2]= StringUtils.isEmpty(configuration.getSwarmNetwork())?
                    String.format("docker run --rm --privileged %s sh -xc '%s' ", dockerSwarmAgentTemplate.getImage(), commands[2]):
                    String.format("docker run --rm --privileged --network %s %s sh -xc '%s' ",configuration.getSwarmNetwork(), dockerSwarmAgentTemplate.getImage(), commands[2]);

            crReq = new ServiceSpec(computer.getName(),"docker:17.12" , commands, envVars, dir, user);
        }else {
            crReq = new ServiceSpec(computer.getName(), dockerSwarmAgentTemplate.getImage(), commands, envVars, dir, user);
        }
        return crReq;
    }

    private void setTmpfs(DockerSwarmAgentTemplate dockerSwarmAgentTemplate, ServiceSpec crReq) {
        if(StringUtils.isNotEmpty(dockerSwarmAgentTemplate.getTmpfsDir())){
            crReq.addTmpfsMount(dockerSwarmAgentTemplate.getTmpfsDir());
        }
    }

    private void setCacheDirs(DockerSwarmCloud configuration, DockerSwarmAgentTemplate dockerSwarmAgentTemplate, TaskListener listener, DockerSwarmComputer computer, ServiceSpec crReq) {
        final String[] cacheDirs = dockerSwarmAgentTemplate.getCacheDirs();
        if (cacheDirs.length > 0) {
            final String cacheVolumeName = getJobName() + "-" + computer.getVolumeName();
            this.bi.getAction(DockerSwarmAgentInfo.class).setCacheVolumeName(cacheVolumeName);
            for (int i = 0; i < cacheDirs.length; i++) {
                listener.getLogger().println("Binding Volume" + cacheDirs[i] + " to " + cacheVolumeName);
                crReq.addCacheVolume(cacheVolumeName, cacheDirs[i], configuration.getCacheDriverName());
            }
        }
    }

    private void setNetwork(DockerSwarmCloud configuration, ServiceSpec crReq) {
        crReq.setNetwork(configuration.getSwarmNetwork());
    }

    private void setHostBinds(DockerSwarmAgentTemplate dockerSwarmAgentTemplate, ServiceSpec crReq) {
        String[] hostBinds = dockerSwarmAgentTemplate.getHostBindsConfig();
        for(int i = 0; i < hostBinds.length; i++){
            String hostBind = hostBinds[i];
            String[] srcDest = hostBind.split(":");
            crReq.addBindVolume(srcDest[0],srcDest[1]);
        }
    }

    private void setSecrets(DockerSwarmAgentTemplate dockerSwarmAgentTemplate, ServiceSpec crReq) {
        String[] secrets = dockerSwarmAgentTemplate.getSecretsConfig();
        if (secrets.length > 0) try {
            final Object secretList = new ListSecretsRequest().execute();
            for (int i = 0; i < secrets.length; i++) {
                String secret = secrets[i];
                String[] split = secret.split(":");
                boolean found = false;
                for (Secret secretEntry : (List<Secret>) getResult(secretList, List.class)) {
                    if(secretEntry.Spec.Name.equals(split[0])) {
                        crReq.addSecret(secretEntry.ID, split[0], split[1]);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    LOGGER.log(Level.WARNING, "Secret {0} not found.", split[0]);
                }
            }
        }
        catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed setting secret", e);
        }
    }

    private void setConfigs(DockerSwarmAgentTemplate dockerSwarmAgentTemplate, ServiceSpec crReq) {
        String[] configs = dockerSwarmAgentTemplate.getConfigsConfig();
        if (configs.length > 0) try {
            final Object configList = new ListConfigsRequest().execute();
            for (int i = 0; i < configs.length; i++) {
                String config = configs[i];
                String[] split = config.split(":");
                boolean found = false;
                for (Config configEntry : (List<Config>) getResult(configList, List.class)) {
                    if(configEntry.Spec.Name.equals(split[0])) {
                        crReq.addConfig(configEntry.ID, split[0], split[1]);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    LOGGER.log(Level.WARNING, "Config {0} not found.", split[0]);
                }
            }
        }
        catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed setting config", e);
        }
    }

    private <T> T  getResult(Object result, Class<T> clazz){
        if(result instanceof SerializationException){
            throw new RuntimeException (((SerializationException)result).getCause());
        }
        if(result instanceof ApiException){
            throw new RuntimeException (((ApiException)result).getCause());
        }
        return clazz.cast(result);
    }

    private void setLimitsAndReservations(DockerSwarmAgentTemplate dockerSwarmAgentTemplate, ServiceSpec crReq) {
        crReq.setTaskLimits(dockerSwarmAgentTemplate.getLimitsNanoCPUs(), dockerSwarmAgentTemplate.getLimitsMemoryBytes() );
        crReq.setTaskReservations(dockerSwarmAgentTemplate.getReservationsNanoCPUs(), dockerSwarmAgentTemplate.getReservationsMemoryBytes() );
    }


    private String getAgentJarUrl(final DockerSwarmCloud configuration) {
        return getJenkinsUrl(configuration) + "jnlpJars/slave.jar";
    }

    private String getAgentJnlpUrl(final Computer computer, final DockerSwarmCloud configuration) {
        return getJenkinsUrl(configuration) + computer.getUrl() + "slave-agent.jnlp";

    }

    private String getAgentSecret(final Computer computer) {
        return ((DockerSwarmComputer) computer).getJnlpMac();

    }

    private String getJenkinsUrl(final DockerSwarmCloud configuration) {
        final String url = configuration.getJenkinsUrl();
        if (url.endsWith("/")) {
            return url;
        } else {
            return url + '/';
        }
    }

    public String getJobName() {
        return this.jobName
                .replaceAll("/", "_")
                .replaceAll("-", "_")
                .replaceAll(",", "_")
                .replaceAll(" ", "_")
                .replaceAll("=", "_")
                .replaceAll("\\.", "_");
    }

}
