/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.forge.rest.main;

import io.fabric8.forge.rest.CommandsResource;
import io.fabric8.forge.rest.dto.CommandInfoDTO;
import io.fabric8.forge.rest.dto.ExecutionRequest;
import io.fabric8.forge.rest.hooks.CommandCompletePostProcessor;
import io.fabric8.forge.rest.producer.FurnaceProducer;
import io.fabric8.forge.rest.utils.StopWatch;
import io.fabric8.project.support.UserDetails;
import org.apache.deltaspike.core.api.config.ConfigProperty;
import org.jboss.forge.furnace.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Initialises Forge add on repository
 */
@Singleton
@javax.ejb.Singleton
@javax.ejb.Startup
public class ForgeInitialiser {
    private static final transient Logger LOG = LoggerFactory.getLogger(ForgeInitialiser.class);
    public static final String DEFAULT_ARCHETYPES_VERSION = "2.2.34";

    /**
     * @param addOnDir the directory where Forge addons will be stored
     */
    @Inject
    public ForgeInitialiser(@ConfigProperty(name = "FORGE_ADDON_DIRECTORY", defaultValue = "./addon-repository") String addOnDir, FurnaceProducer furnaceProducer) {
        java.util.logging.Logger out = java.util.logging.Logger.getLogger(this.getClass().getName());
        out.info("Logging to JUL to test the configuration");

        // lets ensure that the addons folder is initialised
        File repoDir = new File(addOnDir);
        repoDir.mkdirs();
        LOG.info("initialising furnace with folder: " + repoDir.getAbsolutePath());
        File[] files = repoDir.listFiles();
        if (files == null || files.length == 0) {
            LOG.warn("No files found in the addon directory: " + repoDir.getAbsolutePath());
        } else {
            LOG.warn("Found " + files.length + " addon files in directory: " + repoDir.getAbsolutePath());
        }
        furnaceProducer.setup(repoDir);
    }

    public void preloadCommands(CommandsResource commandsResource)  {
        StopWatch watch = new StopWatch();

        LOG.info("Preloading commands");
        List<CommandInfoDTO> commands = Collections.EMPTY_LIST;
        try {
            commands = commandsResource.getCommands();
            LOG.info("Loaded " + commands.size() + " commands");
        } catch (Exception e) {
            LOG.error("Failed to preload commands! " + e, e);
        }

        LOG.info("preloadCommands took " + watch.taken());
    }

    public void preloadProjects(CommandsResource commandsResource, Map<String, Set<String>> catalogs)  {
        StopWatch watch = new StopWatch();

        LOG.info("Preloading projects");
        int i = 0;

        // TODO: do not work due forge classloading issues
        // java.lang.ClassCastException: org.jboss.forge.addon.maven.archetype.ArchetypeCatalogFactory_$$_javassist_52422bd7-4060-45af-87db-4d3a6e9b5664 cannot be cast to org.jboss.forge.addon.maven.archetype.ArchetypeCatalogFactory

        for (Map.Entry<String, Set<String>> entry : catalogs.entrySet()) {

            String catalogName = entry.getKey();

            for (String archetype : entry.getValue()) {

                String tempDir = createTempDirectory();
                if (tempDir == null) {
                    LOG.warn("Cannot create temporary directory");
                    continue;
                }

                i++;

                ExecutionRequest executionRequest = new ExecutionRequest();
                Map<String, Object> step1Inputs = new HashMap<>();
                step1Inputs.put("buildSystem", "Maven");
                String projectName = "dummy-" + i;
                step1Inputs.put("named", projectName);
                step1Inputs.put("targetLocation", tempDir);
                step1Inputs.put("topLevelPackage", "org.example");
                step1Inputs.put("type", "From Archetype Catalog");
                step1Inputs.put("version", "1.0.0-SNAPSHOT");

                Map<String, Object> step2Inputs = new HashMap<>();
                step2Inputs.put("catalog", catalogName);
                step2Inputs.put("archetype", archetype);

                List<Map<String, Object>> inputList = new ArrayList<>();
                inputList.add(step1Inputs);
                inputList.add(step2Inputs);
                executionRequest.setInputList(inputList);
                executionRequest.setWizardStep(2);
                UserDetails userDetails = new UserDetails("someAddress", "someInternalAddress", "dummyUser", "dummyPassword", "dummy@doesNotExist.com");
                try {
                    LOG.info("Now trying to create a new project: " + projectName + " from archetype: " + archetype + " in directory: " + tempDir);
                    CommandCompletePostProcessor postProcessor = null;
                    dumpResult(commandsResource.doExecute("project-new", executionRequest, postProcessor, userDetails, commandsResource.createUIContext(new File(tempDir))));

                    LOG.info("Created project done!");

                } catch (Exception e) {
                    LOG.error("Failed to execute command: " + e, e);
                }
            }
        }

        LOG.info("preloadProjects took " + watch.taken());
    }

    protected String createTempDirectory() {
        try {
            File file = File.createTempFile("project-new", "tmp");
            file.delete();
            file.mkdirs();
            return file.getPath();
        } catch (IOException e) {
            LOG.error("Failed to create temp directory: " + e, e);
        }

        return null;
    }

    protected void dumpResult(Response response) {
        LOG.info("Status: "+ response.getStatus());
        Object entity = response.getEntity();
        if (entity != null) {
            LOG.info("Got entity: "+ entity + " " + entity.getClass().getName());
        }
    }

    protected String getArchetypesVersion() {
        String answer = System.getenv("FABRIC8_ARCHETYPES_VERSION");
        if (Strings.isNullOrEmpty(answer)) {
            return DEFAULT_ARCHETYPES_VERSION;
        }
        return answer;
    }

    public static void removeDir(File d) {
        String[] list = d.list();
        if (list == null) {
            list = new String[0];
        }
        for (String s : list) {
            File f = new File(d, s);
            if (f.isDirectory()) {
                removeDir(f);
            } else {
                f.delete();
            }
        }
        d.delete();
    }

}
