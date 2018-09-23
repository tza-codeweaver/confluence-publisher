/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sahli.asciidoc.confluence.publisher.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.asciidoctor.Attributes;
import org.sahli.asciidoc.confluence.publisher.client.ConfluencePublisher;
import org.sahli.asciidoc.confluence.publisher.client.ConfluencePublisherListener;
import org.sahli.asciidoc.confluence.publisher.client.http.ConfluencePage;
import org.sahli.asciidoc.confluence.publisher.client.http.ConfluenceRestClient;
import org.sahli.asciidoc.confluence.publisher.client.metadata.ConfluencePublisherMetadata;
import org.sahli.asciidoc.confluence.publisher.client.metadata.ConfluencePublisherPublishStrategy;
import org.sahli.asciidoc.confluence.publisher.converter.AsciidocConfluenceConverter;
import org.sahli.asciidoc.confluence.publisher.converter.PageTitlePostProcessor;
import org.sahli.asciidoc.confluence.publisher.converter.PrefixAndSuffixPageTitlePostProcessor;
import org.sahli.asciidoc.confluence.publisher.converter.providers.AsciidocPagesStructureProvider;
import org.sahli.asciidoc.confluence.publisher.converter.providers.FolderBasedAsciidocPagesStructureProvider;
import org.sahli.asciidoc.confluence.publisher.converter.providers.IncludeBasedAsciidocPagesStructureProvider;
import org.sahli.asciidoc.confluence.publisher.converter.providers.IncludeBasedAsciidocPagesStructureProvider.PathDelimiter;
import org.sahli.asciidoc.confluence.publisher.converter.providers.IncludeBasedAsciidocPagesStructureProviderListener;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;

/**
 * @author Alain Sahli
 * @author Christian Stettler
 */
@Mojo(name = "publish")
public class AsciidocConfluencePublisherMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.directory}/asciidoc-confluence-publisher", readonly = true)
    private File confluencePublisherBuildFolder;

    @Parameter
    private File asciidocRootFolder;

    @Parameter(defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "UTF-8")
    private String sourceEncoding;

    @Parameter
    private String rootConfluenceUrl;

    @Parameter(required = true)
    private String spaceKey;

    @Parameter(required = true)
    private String ancestorId;

    @Parameter(defaultValue = "APPEND_TO_ANCESTOR")
    private ConfluencePublisherPublishStrategy strategy;

    @Parameter(defaultValue = "INCLUDE")
    private AsciidocPagesStructureProviderType providerType;

    @Parameter
    private String username;

    @Parameter
    private String password;

    @Parameter
    private Map<String, Object> attributes;

    @Parameter
    private String pageTitlePrefix;

    @Parameter
    private String pageTitleSuffix;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {

            getLog().debug("skipping run as per configuration.");
            return;
        }

        try {
            PageTitlePostProcessor pageTitlePostProcessor = new PrefixAndSuffixPageTitlePostProcessor(pageTitlePrefix, pageTitleSuffix);

            AsciidocPagesStructureProvider asciidocPagesStructureProvider = null;
            switch (providerType) {
                case INCLUDE:
                    IncludeBasedAsciidocPagesStructureProviderListener providerListener = new LoggingIncludeBasedAsciidocPagesStructureProviderListener(getLog());
                    asciidocPagesStructureProvider = new IncludeBasedAsciidocPagesStructureProvider(asciidocRootFolder.toPath(), confluencePublisherBuildFolder.toPath().resolve("preprocessed"), attributes, Charset.forName(sourceEncoding), providerListener);
                    break;
                case FOLDER:
                    asciidocPagesStructureProvider = new FolderBasedAsciidocPagesStructureProvider(asciidocRootFolder.toPath(), Charset.forName(sourceEncoding));
                    break;
            }

            AsciidocConfluenceConverter asciidocConfluenceConverter = new AsciidocConfluenceConverter(spaceKey, ancestorId);
            ConfluencePublisherMetadata confluencePublisherMetadata = asciidocConfluenceConverter.convert(asciidocPagesStructureProvider, pageTitlePostProcessor, confluencePublisherBuildFolder.toPath(), new Attributes(attributes));
            confluencePublisherMetadata.setPublishStrategy(strategy);

            ConfluenceRestClient confluenceRestClient = new ConfluenceRestClient(rootConfluenceUrl, username, password);
            ConfluencePublisherListener confluencePublisherListener = new LoggingConfluencePublisherListener(getLog());

            ConfluencePublisher confluencePublisher = new ConfluencePublisher(confluencePublisherMetadata, confluenceRestClient, confluencePublisherListener);
            confluencePublisher.publish();
        } catch (Exception e) {
            getLog().error("Publishing to Confluence failed: " + e.getMessage());
            throw new MojoExecutionException("Publishing to Confluence failed", e);
        }
    }

    private static enum AsciidocPagesStructureProviderType {
        INCLUDE,
        FOLDER
    }

    private static class LoggingConfluencePublisherListener implements ConfluencePublisherListener {

        private Log log;

        LoggingConfluencePublisherListener(Log log) {
            this.log = log;
        }

        @Override
        public void pageAdded(ConfluencePage addedPage) {
            log.info("Added page '" + addedPage.getTitle() + "' (id " + addedPage.getContentId() + ")");
        }

        @Override
        public void pageUpdated(ConfluencePage existingPage, ConfluencePage updatedPage) {
            log.info("Updated page '" + updatedPage.getTitle() + "' (id " + updatedPage.getContentId() + ", version " + existingPage.getVersion() + " -> " + updatedPage.getVersion() + ")");
        }

        @Override
        public void pageDeleted(ConfluencePage deletedPage) {
            log.info("Deleted page '" + deletedPage.getTitle() + "' (id " + deletedPage.getContentId() + ")");
        }

        @Override
        public void publishCompleted() {
            log.info("Publishing was succesfully completed!");
        }

    }

    private static class LoggingIncludeBasedAsciidocPagesStructureProviderListener implements IncludeBasedAsciidocPagesStructureProviderListener {

        private Log log;

        LoggingIncludeBasedAsciidocPagesStructureProviderListener(Log log) {
            this.log = log;
        }

        @Override
        public void processDirectory(Path directory) {
            log.debug(String.format("Processing directory %s", directory));
        }

        @Override
        public void processFile(Path file, Path targetFile) {
            log.debug(String.format("Processing file %s", file));
        }

        @Override
        public void collectInclude(String path, Path actualPath, Path resultPath, Path file, Path targetFile) {
            log.debug(String.format("Mapping 'include::%s' in %s to %s", path, file, resultPath));
        }

        @Override
        public void rejectInclude(String path, Path actualPath, Path file, Path targetFile) {
            log.debug(String.format("Include reference 'include::%s' in %s will not receive separate page in Confluence", path, file));
        }

        @Override
        public void processInclude(Path file, Path targetFile) {
            log.debug(String.format("Processing (include) page %s", file));
        }

        @Override
        public void changePath(String path, Path actualPath, Path resultPath, Path file, Path targetFile, PathDelimiter delimiter) {
            log.debug(String.format("Changing path '%s%s' in %s to refer to %s", delimiter.start(), path, file, resultPath));
        }

        @Override
        public void missingPath(String path, Path actualPath, Path file, Path targetFile, PathDelimiter delimiter) {
            log.warn(String.format("Ignoring path '%s%s' in %s since not available locally: %s", delimiter.start(), path, file, actualPath));
        }
    }
}
