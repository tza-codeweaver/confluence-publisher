package org.sahli.asciidoc.confluence.publisher.converter.providers;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.unmodifiableList;

public class IncludeBasedAsciidocPagesStructureProvider implements AsciidocPagesStructureProvider {

    private static final String DOC_EXTENSION = ".adoc";
    private static final String DOC_INCLUDE_PREFIX = "_";
    private static final String DOC_INCLUDE_ATTRIBUTES_ASSIGN = "=";
    private static final String DOC_INCLUDE_ATTRIBUTES_SEPARATOR = ",";
    private static final String DOC_INCLUDE_ATTRIBUTES_CONFLUENCE = "confluence";
    private static final String DOC_INCLUDE_ATTRIBUTES_CONFLUENCE_INCLUDE = "include";

    private final Path source;
    private final Path workingDir;
    private final IncludeBasedAsciidocPagesStructureProviderListener listener;
    private final Map<String, Object> attributes;

    private final AsciidocPagesStructure structure;
    private final Charset sourceEncoding;

    public IncludeBasedAsciidocPagesStructureProvider(Path source, Path workingDir, Map<String, Object> attributes, Charset sourceEncoding) {
        this(source, workingDir, attributes, sourceEncoding, new NoOpIncludeBasedAsciidocPagesStructureProviderListener());
    }

    public IncludeBasedAsciidocPagesStructureProvider(Path source, Path workingDir, Map<String, Object> attributes, Charset sourceEncoding, IncludeBasedAsciidocPagesStructureProviderListener listener) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(workingDir);
        Objects.requireNonNull(listener);

        // verify if source exist
        if (!source.toFile().exists()) {
            throw new IllegalArgumentException("Source not found: " + source);
        }

        // verify if source is supported path
        if (!(source.toFile().isDirectory() || isNonPrefixedAdoc(source))) {
            throw new IllegalArgumentException("Invalid source " + source + ". Must be a directory or adoc file.");
        }

        // verify if working is directory
        if (workingDir.toFile().exists() && !workingDir.toFile().isDirectory()) {
            throw new IllegalArgumentException("Working directory is not a directory: " + workingDir);
        }

        this.source = source;
        this.workingDir = workingDir;
        this.attributes = attributes == null ? new HashMap<>() : attributes;
        this.sourceEncoding = sourceEncoding == null ? Charset.defaultCharset() : sourceEncoding;
        this.listener = listener;
        structure = buildStructure();
    }

    @Override
    public AsciidocPagesStructure structure() {
        return structure;
    }

    @Override
    public Charset sourceEncoding() {
        return sourceEncoding;
    }

    private AsciidocPagesStructure buildStructure() {
        try {
            List<AsciidocPage> pages = new ArrayList<>();
            if (source.toFile().isDirectory()) {
                listener.processDirectory(source);
                // assemble all AsciiDoc files from source
                List<Path> adocFiles = Files.list(source)
                    .filter(IncludeBasedAsciidocPagesStructureProvider::isNonPrefixedAdoc)
                    .collect(Collectors.toList());

                // parse each file
                for (Path file : adocFiles) {
                    // use relative path to source to determine targetFile
                    pages.add(parseFile(file, Paths.get(workingDir.toString(), source.relativize(file).toString()).normalize()));
                }
            } else if (isNonPrefixedAdoc(source)) {
                // use relative path to sourceDir to determine targetFile
                pages.add(parseFile(source, Paths.get(workingDir.toString(), source.getFileName().toString()).normalize()));
            }
            return new DefaultAsciidocPagesStructure(pages);
        } catch (IOException e) {
            throw new RuntimeException("Could not create asciidoc source structure", e);
        }
    }

    /**
     * Copy AsciiDoc files to working directory to match the required structure of the Confluence Publisher.
     * Change paths to those files so the references in other files still match, e.g. when using the include directive.
     * References to other resources (image, plantuml, ...) need to be adapted so the relative path
     * points to the original resource.
     * <p>
     * Includes of non-prefixed should be removed inline (since a separate page will be created for it).
     * <p>
     * Resources are assumed to be defined on a single line.
     * Multiline resource definitions are not supported.
     *
     * @param file       original file
     * @param targetFile path where adapted file should be written to
     */
    private AsciidocPage parseFile(Path file, Path targetFile) {
        try {
            // collect all files in include tree so we can adapt them
            Map<Path, Path> linkedFiles = new LinkedHashMap<>();
            AsciidocPage asciidocPage = collectFiles(file, targetFile, linkedFiles);

            // change line with references to other files
            for (Map.Entry<Path, Path> entry : linkedFiles.entrySet()) {
                Path source = entry.getKey();
                Path target = entry.getValue();

                listener.processInclude(source, target);
                List<String> adaptedLines = Files.lines(source)
                    .map(line -> {
                        line = replaceAttributes(line, attributes);
                        return parseLine(line, source, target, linkedFiles);
                    })
                    .collect(Collectors.toList());

                // create folders in working dir if necessary
                target.getParent().toFile().mkdirs();

                // write adapted lines in target file
                Files.write(target, adaptedLines);
            }
            return asciidocPage;
        } catch (IOException e) {
            throw new RuntimeException(String.format("Unable to read/write file %s", file), e);
        }
    }

    private AsciidocPage collectFiles(Path file, Path targetFile, Map<Path, Path> includeMappings) {
        try {
            listener.processFile(file, targetFile);
            DefaultAsciidocPage page = new DefaultAsciidocPage(targetFile);
            includeMappings.put(file, targetFile);
            Files.lines(file).forEach(line -> {
                line = replaceAttributes(line, attributes);
                searchInclude(line, file, targetFile, includeMappings).forEach(page::addChild);
            });
            return page;
        } catch (IOException e) {
            throw new RuntimeException(String.format("Unable to read/write file %s", file), e);
        }
    }

    private String replaceAttributes(String line, Map<String, Object> attributes) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            line = line.replace("{" + entry.getKey() + "}", entry.getValue().toString());
        }
        return line;
    }

    private List<AsciidocPage> searchInclude(String line, Path file, Path targetFile, Map<Path, Path> includeMappings) {
        List<AsciidocPage> pages = new ArrayList<>();
        PathDelimiter delimiter = new PathDelimiter("include::", "[", "]");
        Matcher matcher = delimiter.pattern.matcher(line);
        if (matcher.find()) {
            // actual path to reference
            Path matchedPath = referencePath(file, matcher.group(2));
            Path targetPath = referenceTargetPath(file, targetFile, matchedPath);

            // only include as AsciiDocPage if confluence include flag wasn't added to include attributes or filename has include prefix
            if (!isConfluenceInclude(matcher.group(3)) && isNonPrefixedAdoc(matchedPath)) {
                listener.collectInclude(matcher.group(2), matchedPath, targetPath, file, targetFile);
                pages.add(collectFiles(matchedPath, targetPath, includeMappings));
            } else {
                listener.rejectInclude(matcher.group(2), matchedPath, file, targetFile);
            }
        }
        return pages;
    }

    private String parseLine(String line, Path file, Path targetFile, Map<Path, Path> includeMappings) {
        line = handlePaths(line, file, targetFile, includeMappings,
            new PathDelimiter(":imagesdir: ", "", "", "^(:imagesdir:)(\\s*\\S+)(.*)$"),
            new PathDelimiter("include::", "[", "]"),
            new PathDelimiter("image::", "[", "]"),
            new PathDelimiter("plantuml::", "[", "]"),
            new PathDelimiter("image:", "[", "]", true),
            new PathDelimiter("link:", "[", "]", true),
            new PathDelimiter("<<", "#,", ">>", true)
        );
        return line;
    }

    private String handlePaths(String line, Path file, Path targetFile, Map<Path, Path> includeMappings, PathDelimiter... delimiters) {
        // verify every delimiter so all references are adapted (where necessary)
        for (PathDelimiter delimiter : delimiters) {
            // find resource paths and adapt paths to correct path
            Matcher matcher = delimiter.pattern.matcher(line);
            while (matcher.find()) {
                // actual path to reference
                String path = matcher.group(2).trim();
                Path matchedPath = referencePath(file, path);
                Path targetPath = includeMappings.get(matchedPath);

                // if referenced path exists, then change it to a relative path of the target file in working dir
                if (matchedPath.toFile().exists()) {
                    // if targetPath is null (which means it isn't mapped include)
                    // then make the result path relative to the matched path (meaning to the actual file instead of the target file of the reference)
                    Path resultPath = targetFile.getParent().relativize(targetPath == null ? matchedPath : targetPath);
                    listener.changePath(path, matchedPath, resultPath, file, targetFile, delimiter);
                    line = line.replaceAll(
                        Pattern.quote(delimiter.start.trim()) + matcher.group(2) + Pattern.quote(delimiter.refEnd) + matcher.group(3) + Pattern.quote(delimiter.end),
                        // change path unless it is a included reference in which case it should be removed since a separate page will be created for it
                        targetPath != null ? "" : delimiter.start + resultPath.toString() + delimiter.refEnd + matcher.group(3) + delimiter.end);
                } else {
                    // ignore non-existent file
                    listener.missingPath(path, matchedPath, file, targetFile, delimiter);
                }
            }
        }

        return line;
    }

    private static boolean isNonPrefixedAdoc(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.endsWith(DOC_EXTENSION) && !fileName.startsWith(DOC_INCLUDE_PREFIX);
    }

    private static boolean isConfluenceInclude(String attributes) {
        String[] attributeList = attributes == null ? new String[0] : attributes.split(DOC_INCLUDE_ATTRIBUTES_SEPARATOR);
        for (String attribute : attributeList) {
            String[] attributeParts = attribute.split(DOC_INCLUDE_ATTRIBUTES_ASSIGN);
            if (attributeParts.length == 2 &&
                DOC_INCLUDE_ATTRIBUTES_CONFLUENCE.equals(attributeParts[0]) &&
                DOC_INCLUDE_ATTRIBUTES_CONFLUENCE_INCLUDE.equals(attributeParts[1])) {
                return true;
            }
        }
        return false;
    }

    private static Path referencePath(Path file, String refRelativePath) {
        return Paths.get(file.getParent().toString(), refRelativePath).normalize();
    }

    private static Path referenceTargetPath(Path file, Path targetFile, Path referencePath) {
        // TODO this assumes that referencePaths are always sub paths of file, if this is not the case then the targetPath might be 'outside' the workingDir which we obviously don't want
        return Paths.get(targetFile.getParent().toString(), file.getParent().relativize(referencePath).toString()).normalize();
    }

    public static class PathDelimiter {

        private final String start;
        private final String refEnd;
        private final String end;
        private final Pattern pattern;

        private PathDelimiter(String start, String refEnd, String end) {
            this(start, refEnd, end, false);
        }

        private PathDelimiter(String start, String refEnd, String end, boolean inline) {
            this(start, refEnd, end, String.format("%s(%s)([^%s]*)%s([^%s]*)%s",
                inline ? ".+" : "^", Pattern.quote(start), Pattern.quote(refEnd), Pattern.quote(refEnd), Pattern.quote(end), Pattern.quote(end)));
        }

        private PathDelimiter(String start, String refEnd, String end, String pattern) {
            this.start = start;
            this.refEnd = refEnd;
            this.end = end;
            this.pattern = Pattern.compile(pattern);
        }

        public String start() {
            return start;
        }
    }

    private static class DefaultAsciidocPage implements AsciidocPage {

        private final Path path;
        private final List<AsciidocPage> children;

        DefaultAsciidocPage(Path path) {
            this.path = path;
            children = new ArrayList<>();
        }

        void addChild(AsciidocPage child) {
            children.add(child);
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public List<AsciidocPage> children() {
            return unmodifiableList(children);
        }
    }


    private static class DefaultAsciidocPagesStructure implements AsciidocPagesStructure {

        private final List<AsciidocPage> asciidocPages;

        DefaultAsciidocPagesStructure(List<AsciidocPage> asciidocPages) {
            this.asciidocPages = asciidocPages;
        }

        @Override
        public List<AsciidocPage> pages() {
            return asciidocPages;
        }
    }
}
