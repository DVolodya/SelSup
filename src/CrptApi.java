import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CrptApi {
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final ReentrantLock lock;
    private final List<LocalDateTime> requestTimestamps;

    // Константы для API
    private static final String BASE_URL = "https://ismp.crpt.ru/api/v3";
    private static final String CREATE_DOCUMENT_PATH = "/lk/documents/create";

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }

        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.semaphore = new Semaphore(requestLimit);
        this.lock = new ReentrantLock();
        this.requestTimestamps = new ArrayList<>();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public ApiResponse createDocument(Document document, String signature) {
        try {
            acquirePermission();

            String requestBody = objectMapper.writeValueAsString(document);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + CREATE_DOCUMENT_PATH))
                    .header("Content-Type", "application/json")
                    .header("Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return processResponse(response);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ApiResponse(false, "Request was interrupted: " + e.getMessage());
        } catch (JsonProcessingException e) {
            return new ApiResponse(false, "JSON serialization error: " + e.getMessage());
        } catch (IOException e) {
            return new ApiResponse(false, "IO error: " + e.getMessage());
        } catch (Exception e) {
            return new ApiResponse(false, "Unexpected error: " + e.getMessage());
        }
    }

    private void acquirePermission() throws InterruptedException {
        lock.lock();
        try {
            cleanupOldTimestamps();

            if (requestTimestamps.size() >= requestLimit) {
                LocalDateTime oldestTimestamp = requestTimestamps.get(0);
                LocalDateTime now = LocalDateTime.now();
                long timeToWait = calculateTimeToWait(oldestTimestamp, now);

                if (timeToWait > 0) {
                    Thread.sleep(timeToWait);
                    cleanupOldTimestamps();
                }
            }

            requestTimestamps.add(LocalDateTime.now());

        } finally {
            lock.unlock();
        }
    }


    private void cleanupOldTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        requestTimestamps.removeIf(timestamp -> {
            long timePassed = getTimeUnitMillis(now) - getTimeUnitMillis(timestamp);
            return timePassed >= timeUnit.toMillis(1);
        });
    }


    private long calculateTimeToWait(LocalDateTime oldestTimestamp, LocalDateTime now) {
        long timePassed = getTimeUnitMillis(now) - getTimeUnitMillis(oldestTimestamp);
        long timeUnitMillis = timeUnit.toMillis(1);
        return timeUnitMillis - timePassed;
    }


    private long getTimeUnitMillis(LocalDateTime dateTime) {
        switch (timeUnit) {
            case SECONDS: return dateTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
            case MINUTES: return dateTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000 / 60;
            case HOURS: return dateTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000 / 3600;
            case DAYS: return dateTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000 / 86400;
            default: return dateTime.toEpochSecond(java.time.ZoneOffset.UTC) * 1000;
        }
    }


    private ApiResponse processResponse(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        String body = response.body();

        if (statusCode >= 200 && statusCode < 300) {
            return new ApiResponse(true, "Document created successfully", body);
        } else {
            return new ApiResponse(false, "HTTP error " + statusCode + ": " + body);
        }
    }


    public static class ApiResponse {
        private final boolean success;
        private final String message;
        private final String data;

        public ApiResponse(boolean success, String message) {
            this(success, message, null);
        }

        public ApiResponse(boolean success, String message, String data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getData() { return data; }
    }


    public static class Document {
        private Description description;
        private String docId;
        private String docStatus;
        private String docType;
        private boolean importRequest;
        private String ownerInn;
        private String participantInn;
        private String producerInn;
        private String productionDate;
        private String productionType;
        private List<Product> products;
        private String regDate;
        private String regNumber;

        public Document() {}

        public Document(Description description, String docId, String docType,
                        String ownerInn, String participantInn, String producerInn,
                        String productionDate, String productionType, List<Product> products) {
            this.description = description;
            this.docId = docId;
            this.docType = docType;
            this.ownerInn = ownerInn;
            this.participantInn = participantInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.productionType = productionType;
            this.products = products;
        }

        public Description getDescription() { return description; }
        public void setDescription(Description description) { this.description = description; }

        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }

        public String getDocStatus() { return docStatus; }
        public void setDocStatus(String docStatus) { this.docStatus = docStatus; }

        public String getDocType() { return docType; }
        public void setDocType(String docType) { this.docType = docType; }

        public boolean isImportRequest() { return importRequest; }
        public void setImportRequest(boolean importRequest) { this.importRequest = importRequest; }

        public String getOwnerInn() { return ownerInn; }
        public void setOwnerInn(String ownerInn) { this.ownerInn = ownerInn; }

        public String getParticipantInn() { return participantInn; }
        public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }

        public String getProducerInn() { return producerInn; }
        public void setProducerInn(String producerInn) { this.producerInn = producerInn; }

        public String getProductionDate() { return productionDate; }
        public void setProductionDate(String productionDate) { this.productionDate = productionDate; }

        public String getProductionType() { return productionType; }
        public void setProductionType(String productionType) { this.productionType = productionType; }

        public List<Product> getProducts() { return products; }
        public void setProducts(List<Product> products) { this.products = products; }

        public String getRegDate() { return regDate; }
        public void setRegDate(String regDate) { this.regDate = regDate; }

        public String getRegNumber() { return regNumber; }
        public void setRegNumber(String regNumber) { this.regNumber = regNumber; }
    }


    public static class Description {
        private String participantInn;

        public Description() {}
        public Description(String participantInn) { this.participantInn = participantInn; }

        public String getParticipantInn() { return participantInn; }
        public void setParticipantInn(String participantInn) { this.participantInn = participantInn; }
    }

    public static class Product {
        private String certificateDocument;
        private String certificateDocumentDate;
        private String certificateDocumentNumber;
        private String ownerInn;
        private String producerInn;
        private String productionDate;
        private String tnvedCode;
        private String uitCode;
        private String uituCode;

        public Product() {}

        public Product(String certificateDocument, String certificateDocumentDate,
                       String certificateDocumentNumber, String ownerInn, String producerInn,
                       String productionDate, String tnvedCode, String uitCode, String uituCode) {
            this.certificateDocument = certificateDocument;
            this.certificateDocumentDate = certificateDocumentDate;
            this.certificateDocumentNumber = certificateDocumentNumber;
            this.ownerInn = ownerInn;
            this.producerInn = producerInn;
            this.productionDate = productionDate;
            this.tnvedCode = tnvedCode;
            this.uitCode = uitCode;
            this.uituCode = uituCode;
        }

        public String getCertificateDocument() { return certificateDocument; }
        public void setCertificateDocument(String certificateDocument) { this.certificateDocument = certificateDocument; }

        public String getCertificateDocumentDate() { return certificateDocumentDate; }
        public void setCertificateDocumentDate(String certificateDocumentDate) { this.certificateDocumentDate = certificateDocumentDate; }

        public String getCertificateDocumentNumber() { return certificateDocumentNumber; }
        public void setCertificateDocumentNumber(String certificateDocumentNumber) { this.certificateDocumentNumber = certificateDocumentNumber; }

        public String getOwnerInn() { return ownerInn; }
        public void setOwnerInn(String ownerInn) { this.ownerInn = ownerInn; }

        public String getProducerInn() { return producerInn; }
        public void setProducerInn(String producerInn) { this.producerInn = producerInn; }

        public String getProductionDate() { return productionDate; }
        public void setProductionDate(String productionDate) { this.productionDate = productionDate; }

        public String getTnvedCode() { return tnvedCode; }
        public void setTnvedCode(String tnvedCode) { this.tnvedCode = tnvedCode; }

        public String getUitCode() { return uitCode; }
        public void setUitCode(String uitCode) { this.uitCode = uitCode; }

        public String getUituCode() { return uituCode; }
        public void setUituCode(String uituCode) { this.uituCode = uituCode; }
    }
}