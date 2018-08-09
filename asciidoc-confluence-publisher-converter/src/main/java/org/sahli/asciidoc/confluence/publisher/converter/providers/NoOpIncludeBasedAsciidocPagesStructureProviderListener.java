package org.sahli.asciidoc.confluence.publisher.converter.providers;

import org.sahli.asciidoc.confluence.publisher.converter.providers.IncludeBasedAsciidocPagesStructureProvider.PathDelimiter;

import java.nio.file.Path;

public class NoOpIncludeBasedAsciidocPagesStructureProviderListener implements IncludeBasedAsciidocPagesStructureProviderListener {

    @Override
    public void processDirectory(Path directory) {
    }

    @Override
    public void processFile(Path file, Path targetFile) {
    }

    @Override
    public void collectInclude(String path, Path actualPath, Path resultPath, Path file, Path targetFile) {
    }

    @Override
    public void rejectInclude(String path, Path actualPath, Path file, Path targetFile) {
    }

    @Override
    public void processInclude(Path file, Path targetFile) {
    }

    @Override
    public void changePath(String path, Path actualPath, Path resultPath, Path file, Path targetFile, PathDelimiter delimiter) {
    }

    @Override
    public void missingPath(String path, Path actualPath, Path file, Path targetFile, PathDelimiter delimiter) {
    }
}
