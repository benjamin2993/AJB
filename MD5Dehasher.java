import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

public class MD5Dehasher {
    private static String targetHash;
    private static int THREAD_COUNT;
    private static AtomicLong tries = new AtomicLong(0);
    private static volatile boolean found = false;
    private static String[] lastCandidates;
    private static final String PROGRESS_FILE = "prog.pro";
    private static final String RESULT_FILE = "result.pro";  // New result file
    private static final long SAVE_INTERVAL = 5000;

    public static void main(String[] args) throws InterruptedException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter the MD5 hash to dehash: ");
        targetHash = scanner.nextLine().trim();

        System.out.print("Enter the number of threads: ");
        THREAD_COUNT = Integer.parseInt(scanner.nextLine().trim());

        lastCandidates = new String[THREAD_COUNT];
        ResumeState[] resumeStates = loadProgress(targetHash);

        long startTime = System.currentTimeMillis();

        Thread[] threads = new Thread[THREAD_COUNT];
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            final ResumeState resumeState = resumeStates[i];
            threads[i] = new Thread(() -> bruteForce(threadId, resumeState));
            threads[i].start();
        }

        Thread monitor = new Thread(() -> {
            long lastCheck = System.currentTimeMillis();
            long lastTries = 0;
            while (!found) {
                try {
                    Thread.sleep(1000);
                    long now = System.currentTimeMillis();
                    long currentTries = tries.get();
                    long triesPerSecond = (currentTries - lastTries) * 1000 / (now - lastCheck);
                    System.out.printf("\rTries/second: %d, Total tries: %d", triesPerSecond, currentTries);
                    lastTries = currentTries;
                    lastCheck = now;
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitor.start();

        Thread autosave = new Thread(() -> {
            while (!found) {
                try {
                    Thread.sleep(SAVE_INTERVAL);
                    saveProgress(targetHash, lastCandidates);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        autosave.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveProgress(targetHash, lastCandidates);
            System.out.println("\nProgress saved!");
        }));

        for (Thread thread : threads) {
            thread.join();
        }

        monitor.interrupt();
        autosave.interrupt();

        long endTime = System.currentTimeMillis();
        System.out.printf("\nTotal tries: %d, Time elapsed: %.2f seconds%n",
                tries.get(), (endTime - startTime) / 1000.0);
    }

    private static void bruteForce(int threadId, ResumeState resumeState) {
        char startC1 = resumeState.c1;
        char startC2 = resumeState.c2;
        char startC3 = resumeState.c3;
        char startC4 = resumeState.c4;
        int startNum = resumeState.num;

        lastCandidates[threadId] = startC1 + "" + startC2 + startC3 + startC4 + String.format("%04d", startNum);

        outerLoop:
        for (char c1 = startC1; c1 <= 'Z' && !found; c1++) {
            for (char c2 = (c1 == startC1 ? startC2 : 'A'); c2 <= 'Z' && !found; c2++) {
                for (char c3 = (c1 == startC1 && c2 == startC2 ? startC3 : 'A'); c3 <= 'Z' && !found; c3++) {
                    for (char c4 = (c1 == startC1 && c2 == startC2 && c3 == startC3 ? startC4 : 'A'); c4 <= 'Z' && !found; c4++) {
                        for (int num = (c1 == startC1 && c2 == startC2 && c3 == startC3 && c4 == startC4 ? startNum : 0);
                             num <= 9999 && !found; num++) {

                            if ((num % THREAD_COUNT) != threadId) continue;

                            String candidate = "" + c1 + c2 + c3 + c4 + String.format("%04d", num);

                            lastCandidates[threadId] = candidate;
                            tries.incrementAndGet();

                            if (tries.get() % 1000000 == 0) {
                                System.out.printf("\rCurrent password: %s", candidate);
                            }

                            if (hash(candidate).equalsIgnoreCase(targetHash)) {
                                System.out.printf("\nMatch found by thread %d: %s%n", threadId, candidate);
                                found = true;
                                saveResult(targetHash, candidate);  // Save the result
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashBytes = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

private static void saveProgress(String hash, String[] candidates) {
    File file = new File(PROGRESS_FILE);
    StringBuilder newContent = new StringBuilder();
    boolean hashFound = false;

    try {
        // Read existing progress file
        if (file.exists()) {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals(hash)) {
                    hashFound = true;
                    newContent.append(hash).append("\n");
                    for (String candidate : candidates) {
                        newContent.append(candidate != null ? candidate : "").append("\n");
                    }
                    // Skip old progress for this hash
                    for (int i = 0; i < THREAD_COUNT; i++) {
                        reader.readLine();
                    }
                } else {
                    newContent.append(line).append("\n");
                }
            }
            reader.close();
        }

        // If the hash was not found, append new progress
        if (!hashFound) {
            newContent.append(hash).append("\n");
            for (String candidate : candidates) {
                newContent.append(candidate != null ? candidate : "").append("\n");
            }
        }

        // Write back the updated content
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(newContent.toString());
        writer.close();

    } catch (IOException e) {
        System.err.println("Error saving progress: " + e.getMessage());
    }
}

    // New method to save the found result
    private static void saveResult(String hash, String foundText) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(RESULT_FILE, true))) {
            writer.write("Hash: " + hash + ", Found text: " + foundText + "\n");
        } catch (IOException e) {
            System.err.println("Error saving result: " + e.getMessage());
        }
    }

    private static ResumeState[] loadProgress(String hash) {
    File file = new File(PROGRESS_FILE);
    ResumeState[] resumes = new ResumeState[THREAD_COUNT];
    for (int i = 0; i < THREAD_COUNT; i++) {
        resumes[i] = new ResumeState('A', 'A', 'A', 'A', 0);
    }

    if (!file.exists()) return resumes;

    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals(hash)) {
                System.out.println("Resuming progress for hash: " + hash);
                for (int i = 0; i < THREAD_COUNT; i++) {
                    String lastCandidate = reader.readLine();
                    if (lastCandidate != null && !lastCandidate.isEmpty()) {
                        resumes[i] = parseResumeState(lastCandidate);
                    }
                }
                return resumes;
            }
        }
    } catch (IOException e) {
        System.err.println("Error loading progress: " + e.getMessage());
    }
    return resumes;
}
    private static ResumeState parseResumeState(String candidate) {
        char c1 = candidate.charAt(0);
        char c2 = candidate.charAt(1);
        char c3 = candidate.charAt(2);
        char c4 = candidate.charAt(3);
        int num = Integer.parseInt(candidate.substring(4));
        return new ResumeState(c1, c2, c3, c4, num);
    }

    private static class ResumeState {
        char c1, c2, c3, c4;
        int num;

        ResumeState(char c1, char c2, char c3, char c4, int num) {
            this.c1 = c1;
            this.c2 = c2;
            this.c3 = c3;
            this.c4 = c4;
            this.num = num;
        }
    }
}