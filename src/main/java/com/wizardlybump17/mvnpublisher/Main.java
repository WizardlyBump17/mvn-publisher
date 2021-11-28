package com.wizardlybump17.mvnpublisher;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class Main {

    public static final Pattern VERSION = Pattern.compile("\\b(-\\d+(?:\\.\\d+)+)\\b");

    public static void main(String[] args) {
        String groupId = System.getProperty("groupId");
        validate(groupId, "groupId");

        String version = System.getProperty("version");
        validate(version, "version");

        String remote = System.getProperty("remote");
        validate(remote, "remote");

        String branch = System.getProperty("branch");
        validate(branch, "branch");

        File folder = new File(System.getProperty("user.dir"));
        Map<File, String> files = findFiles(folder, Boolean.getBoolean("selfIgnore"));

        System.out.println("Detected the following files:");
        files.forEach((file, name) -> System.out.println(file + " as " + name));

        System.out.println("Starting publishing...\n");

        boolean successfullyPublished = publishToMaven(files, folder, groupId, version);
        if (!successfullyPublished)
            return;

        System.out.println("\n");
        publishToGit(folder, remote, branch, new ArrayList<>(files.keySet()), System.getProperty("commitMessage", "update maven repo"));
    }

    private static void validate(String string, String name) {
        if (string == null || string.isEmpty()) {
            System.out.println(name + " cannot be null or empty!");
            System.exit(1);
        }
    }

    /**
     * Will try to find the files to be published. Only accepts .jar files.
     * If the file contains a version (test-1.0.0.jar) it will be removed from the file name (the file name itself won't be changed).
     * The map key is the raw file, and the map value is the name to be used as artifactId
     * @param folder the folder that have the files
     * @param ignoreCallerFile if it must ignore the file that is calling it (the current executable file)
     * @return a map containing the files we found
     */
    public static Map<File, String> findFiles(@NotNull File folder, boolean ignoreCallerFile) {
        File[] files = folder.listFiles(file -> file.getName().endsWith(".jar"));

        if (files == null)
            throw new IllegalArgumentException("the specified file is not a folder");

        if (files.length == 0) {
            System.out.println("The current folder is empty or it don't have valid files!");
            return Collections.emptyMap();
        }

        Map<File, String> map = new HashMap<>(files.length);

        String currentFile = getCallerFile().getAbsolutePath();

        for (File file : files) {
            String name = file.getName();
            if (currentFile.equalsIgnoreCase(file.getAbsolutePath()) && ignoreCallerFile)
                continue;

            name = name.replaceFirst(VERSION.pattern(), "");
            name = name.substring(0, name.length() - 4); //.jar

            map.put(file, name);
        }

        return map;
    }

    /**
     * It will publish the files to the local Maven repository
     * @param files the files to be published
     * @param repository the Maven repository
     * @param groupId the groupId to be used
     * @param version the version to be used
     * @return if any Maven build failed
     */
    public static boolean publishToMaven(@NotNull Map<File, String> files, @NotNull File repository, @NotNull String groupId, @NotNull String version) {
        if (files.isEmpty())
            throw new IllegalArgumentException("empty folder (do your current folder contains any valid file?)");
        if (!repository.isDirectory())
            throw new IllegalArgumentException("specified repository is not a folder");

        AtomicBoolean success = new AtomicBoolean(true);
        for (Map.Entry<File, String> entry : files.entrySet()) {
            File file = entry.getKey();
            String name = entry.getValue();

            try {
                System.out.println("Starting Maven publishing for " + file);

                execAndPrint(
                        "mvn install:install-file -DgroupId=" + groupId + " -DartifactId=" + name + " -Dversion=" + version + " -Dfile=" + file.getAbsolutePath() + " -Dpackaging=jar -DlocalRepositoryPath=" + repository.getAbsolutePath() + " -DcreateChecksum=true -DgeneratePom=true",
                        line -> {
                            if (line.contains("BUILD FAILURE"))
                                success.set(false);
                        }
                );

                System.out.println("\nEnded Maven publication for " + file + "\n");
            } catch (Exception e) {
                System.out.println("Error while handling " + file);
                e.printStackTrace();
            }
        }

        System.out.println("Done");
        return success.get();
    }

    /**
     * It will publish all files in the specified repository to the Git (ignoring the specified files)
     * @param repository where the repository is
     * @param remote the remote name
     * @param branch the branch name
     * @param ignoredFiles the ignored files, the ones that won't be published
     * @param commitMessage the commit message
     */
    public static void publishToGit(@NotNull File repository, @NotNull String remote, @NotNull String branch, @NotNull List<File> ignoredFiles, @NotNull String commitMessage) {
        if (!repository.isDirectory())
            throw new IllegalArgumentException("specified file is not folder");

        File[] gitFile = repository.listFiles(file -> file.getName().equals(".git"));
        if (gitFile.length == 0)
            throw new IllegalArgumentException("specified folder is not a git repository");

        try {
            File callerFile = getCallerFile();
            for (File file : repository.listFiles()) {
                if (file.equals(callerFile) || ignoredFiles.contains(file) || file.getName().equals(".git"))
                    continue;

                execAndPrint("git add " + file.getName(), null);
            }
            execAndPrint("git commit -m \"" + commitMessage + "\"", null);
            System.out.println();
            execAndPrint("git push " + remote + " " + branch, null);

            System.out.println("Pushed to " + branch);
        } catch (Exception e) {
            System.out.println("Error while pushing to " + branch);
            e.printStackTrace();
        }
    }

    private static void execAndPrint(String command, Consumer<String> consumer) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                if (consumer != null)
                    consumer.accept(line);
            }

            reader.close();
        } catch (InterruptedException e) {
            System.out.println("Error while running " + command);
            e.printStackTrace();
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (e.getMessage().contains("CreateProcess error=2")) {
                execAndPrint("cmd /c " + command, consumer);
                return;
            }
            System.out.println("Error while running " + command);
            e.printStackTrace();
        } catch (Exception e) {
            System.out.println("Error while running " + command);
            e.printStackTrace();
        }
    }

    private static File getCallerFile() {
        return new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
    }
}
