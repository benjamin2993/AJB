import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

public class PDFBruteForcerPDFBox {

    public static void main(String[] args) throws IOException, InterruptedException {
        BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));

        // Get PDF and Wordlist paths from user
        System.out.print("Enter PDF file path: ");
        String pdfFile = consoleReader.readLine().trim();

        System.out.print("Enter wordlist file path: ");
        String wordlistFile = consoleReader.readLine().trim();

        System.out.print("Enter the number of threads to use: ");
        int threadCount = Integer.parseInt(consoleReader.readLine().trim());

        // Atomic counter for progress and tracking
        AtomicInteger passwordCounter = new AtomicInteger(0);
        AtomicInteger passwordsPerSecond = new AtomicInteger(0);
        boolean[] passwordFound = {false};

        // Executor service for managing threads
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        BlockingQueue<String> taskQueue = new LinkedBlockingQueue<>(1000);

        // Start worker threads
        for (int i = 0; i < threadCount; i++) {
            executor.submit(new Worker(taskQueue, pdfFile, passwordCounter, passwordsPerSecond, passwordFound));
        }

        // Start real-time password attempts per second tracking
        ScheduledExecutorService statsExecutor = Executors.newScheduledThreadPool(1);
        statsExecutor.scheduleAtFixedRate(() -> {
            System.out.println("Passwords/second: " + passwordsPerSecond.getAndSet(0));
        }, 1, 1, TimeUnit.SECONDS);

        // Read wordlist and distribute passwords
        try (BufferedReader reader = new BufferedReader(new FileReader(wordlistFile))) {
            String password;
            while ((password = reader.readLine()) != null && !passwordFound[0]) {
                taskQueue.put(password.trim());
            }
        }

        // Signal workers to stop
        for (int i = 0; i < threadCount; i++) {
            taskQueue.put("EOF");
        }

        // Wait for all threads to complete
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

        statsExecutor.shutdown();
        statsExecutor.awaitTermination(1, TimeUnit.SECONDS);

        if (!passwordFound[0]) {
            System.out.println("No success: Password not found in the wordlist.");
        }
    }

    // Worker class for processing passwords
    static class Worker implements Runnable {
        private final BlockingQueue<String> taskQueue;
        private final String pdfFile;
        private final AtomicInteger passwordCounter;
        private final AtomicInteger passwordsPerSecond;
        private final boolean[] passwordFound;

        Worker(BlockingQueue<String> taskQueue, String pdfFile, AtomicInteger passwordCounter,
               AtomicInteger passwordsPerSecond, boolean[] passwordFound) {
            this.taskQueue = taskQueue;
            this.pdfFile = pdfFile;
            this.passwordCounter = passwordCounter;
            this.passwordsPerSecond = passwordsPerSecond;
            this.passwordFound = passwordFound;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    String password = taskQueue.take();
                    if ("EOF".equals(password)) {
                        break;
                    }

                    if (passwordFound[0]) {
                        break; // Stop if password is already found
                    }

                    // Attempt to open the PDF
                    try (PDDocument doc = Loader.loadPDF(new File(pdfFile), password)) {
                        System.out.println("Password found: " + password);
                        passwordFound[0] = true;
                        System.exit(0); // Exit program
                    } catch (InvalidPasswordException ignored) {
                        // Password is incorrect; continue to the next
                    }

                    // Update counters
                    int count = passwordCounter.incrementAndGet();
                    passwordsPerSecond.incrementAndGet();

                    // Print progress every 10,000 attempts
                    if (count % 10000 == 0) {
                        System.out.println(count + " passwords tried...");
                    }
                } catch (InterruptedException | IOException e) {
                    Thread.currentThread().interrupt();
                    e.printStackTrace();
                }
            }
        }
    }
}