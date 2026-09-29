package com.example.myapplication;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CODE = 101;

    // ================== আপনার গুগল শিটের পাবলিশড CSV লিংক ==================
    private static final String STOCK_SHEET_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vR5RLl9fVBxpbJ-8O3BZllSRQy_1XV3tRJoqyofjThOMGBhAM1Ef6OuJ3rLr41AYAkK64a3KC6s5k_r/pub?gid=0&single=true&output=csv";
    private static final String PURCHASE_SHEET_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vQ9h4YoDux9zsT9Ig-GGgx360QNYBvjYeuoGY_PfSECKoh6yuar9kCdOkzG2xs61O5qNqVsrIAjFPt0/pub?gid=0&single=true&output=csv";
    // ======================================================================

    private PreviewView previewView;
    private TextView tvLastScanned, tvStockCount;
    private EditText etLocation;
    private AutoCompleteTextView etManualChassis;
    private Button btnAddManual, btnSyncSheets, btnViewReport, btnExportFiles, btnScannerStatus, btnNextScan;

    private TextRecognizer textRecognizer;
    private SharedPreferences sharedPreferences;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Set<String> scannedChassisSet = new HashSet<>();

    private List<ScannedModel> stockMatchList = new ArrayList<>();
    private List<ScannedModel> purchaseMatchList = new ArrayList<>();
    private List<ScannedModel> unknownList = new ArrayList<>();

    private Set<String> stockSheetList = new HashSet<>();
    private Set<String> purchaseSheetList = new HashSet<>();
    private final List<String> allSheetChassisForSuggestion = new ArrayList<>();

    private boolean isScannerOn = true;
    private boolean isWaitingForNextScan = false;

    private Camera camera;
    private ScaleGestureDetector scaleGestureDetector;

    public static class ScannedModel {
        String chassis;
        String location;
        String timestamp;
        String status;

        public ScannedModel(String chassis, String location, String timestamp, String status) {
            this.chassis = chassis;
            this.location = location;
            this.timestamp = timestamp;
            this.status = status;
        }

        String serialize() {
            return chassis + "||" + location + "||" + timestamp + "||" + status;
        }

        static ScannedModel deserialize(String raw) {
            String[] parts = raw.split("\\|\\|");
            if (parts.length == 4) {
                return new ScannedModel(parts[0], parts[1], parts[2], parts[3]);
            }
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        tvLastScanned = findViewById(R.id.tvLastScanned);
        tvStockCount = findViewById(R.id.tvStockCount);
        etLocation = findViewById(R.id.etLocation);
        etLocation.setInputType(InputType.TYPE_CLASS_TEXT);
        etManualChassis = findViewById(R.id.etManualChassis);
        btnAddManual = findViewById(R.id.btnAddManual);
        btnSyncSheets = findViewById(R.id.btnSyncSheets);
        btnViewReport = findViewById(R.id.btnViewReport);
        btnExportFiles = findViewById(R.id.btnExportFiles);
        btnScannerStatus = findViewById(R.id.btnScannerStatus);
        btnNextScan = findViewById(R.id.btnNextScan);

        sharedPreferences = getSharedPreferences("ChassisReports", Context.MODE_PRIVATE);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        setupManualEntryAutoSuggest();
        loadReportsFromStorage();
        fetchSheetDataFromGoogle(true);
        checkPermissions();

        btnAddManual.setOnClickListener(v -> {
            String chassis = etManualChassis.getText().toString().trim().toUpperCase(Locale.getDefault());
            if (!TextUtils.isEmpty(chassis)) {
                if (scannedChassisSet.contains(chassis)) {
                    Toast.makeText(this, "Duplicate Chassis Ignored!", Toast.LENGTH_SHORT).show();
                } else {
                    processAndSaveChassis(chassis);
                    etManualChassis.setText("");
                }
            }
        });

        btnScannerStatus.setOnClickListener(v -> {
            isScannerOn = !isScannerOn;
            btnScannerStatus.setText("Scanner Status: " + (isScannerOn ? "ON" : "OFF"));
            btnScannerStatus.setBackgroundColor(isScannerOn ? 0xFF2E7D32 : 0xFFC62828);
        });

        btnSyncSheets.setOnClickListener(v -> fetchSheetDataFromGoogle(false));
        btnViewReport.setOnClickListener(v -> showScannedSummaryDialog());
        btnExportFiles.setOnClickListener(v -> showExportOptionsDialog());

        btnNextScan.setOnClickListener(v -> {
            isWaitingForNextScan = false;
            Toast.makeText(this, "Ready for next scan", Toast.LENGTH_SHORT).show();
        });

        setupPinchToZoomAndFocus();
    }

    // =====================================================================
    // ১) উন্নত অটো-সাজেশন (শেষের ডিজিট দিয়েও খুঁজবে)
    // =====================================================================
    private void setupManualEntryAutoSuggest() {
        ChassisSuggestionAdapter adapter = new ChassisSuggestionAdapter(this, allSheetChassisForSuggestion);
        etManualChassis.setAdapter(adapter);
        etManualChassis.setThreshold(1);

        etManualChassis.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !allSheetChassisForSuggestion.isEmpty()) {
                etManualChassis.showDropDown();
            }
        });
        etManualChassis.setOnClickListener(v -> {
            if (!allSheetChassisForSuggestion.isEmpty()) etManualChassis.showDropDown();
        });
    }

    private void refreshSuggestionAdapter() {
        ChassisSuggestionAdapter adapter = new ChassisSuggestionAdapter(this, allSheetChassisForSuggestion);
        etManualChassis.setAdapter(adapter);
    }

    // কাস্টম অ্যাডাপ্টার — contains দিয়ে ম্যাচ করে (শেষের ডিজিটও কাজ করবে)
    private static class ChassisSuggestionAdapter extends ArrayAdapter<String> {
        private final List<String> originalList;
        private List<String> filteredList;

        public ChassisSuggestionAdapter(Context context, List<String> items) {
            super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(items));
            this.originalList = new ArrayList<>(items);
            this.filteredList = new ArrayList<>(items);
        }

        @Override
        public int getCount() {
            return filteredList.size();
        }

        @Override
        public String getItem(int position) {
            return filteredList.get(position);
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<String> suggestions = new ArrayList<>();

                    if (constraint == null || constraint.length() == 0) {
                        suggestions.addAll(originalList);
                    } else {
                        String filterPattern = constraint.toString().toUpperCase(Locale.getDefault()).trim();
                        for (String chassis : originalList) {
                            // যেকোনো জায়গায় মিললেই সাজেস্ট করবে (শেষের ডিজিটসহ)
                            if (chassis.toUpperCase(Locale.getDefault()).contains(filterPattern)) {
                                suggestions.add(chassis);
                            }
                        }
                    }
                    results.values = suggestions;
                    results.count = suggestions.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    filteredList = (List<String>) results.values;
                    if (results.count > 0) {
                        notifyDataSetChanged();
                    } else {
                        notifyDataSetInvalidated();
                    }
                }
            };
        }
    }

    // =====================================================================
    // গুগল শিট সিঙ্ক
    // =====================================================================
    private void fetchSheetDataFromGoogle(boolean silent) {
        networkExecutor.execute(() -> {
            Set<String> newStock = downloadChassisListFromCsv(STOCK_SHEET_CSV_URL);
            Set<String> newPurchase = downloadChassisListFromCsv(PURCHASE_SHEET_CSV_URL);

            mainHandler.post(() -> {
                if (newStock.isEmpty() && newPurchase.isEmpty()) {
                    Toast.makeText(this, "শিট সিঙ্ক ব্যর্থ। ইন্টারনেট চেক করুন।", Toast.LENGTH_LONG).show();
                    return;
                }
                stockSheetList = newStock;
                purchaseSheetList = newPurchase;

                allSheetChassisForSuggestion.clear();
                allSheetChassisForSuggestion.addAll(stockSheetList);
                allSheetChassisForSuggestion.addAll(purchaseSheetList);
                refreshSuggestionAdapter();

                tvStockCount.setText("Stock: " + stockSheetList.size() + " | Purchase: " + purchaseSheetList.size());
                if (!silent) Toast.makeText(this, "Sheets Synced Successfully!", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private Set<String> downloadChassisListFromCsv(String csvUrl) {
        Set<String> result = new LinkedHashSet<>();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(csvUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            boolean headerSkipped = false;
            int chassisColumnIndex = 1;

            while ((line = reader.readLine()) != null) {
                if (TextUtils.isEmpty(line.trim())) continue;
                String[] cols = line.split(",", -1);

                if (!headerSkipped) {
                    headerSkipped = true;
                    for (int i = 0; i < cols.length; i++) {
                        if (cols[i].toUpperCase(Locale.getDefault()).contains("CHASSIS")) {
                            chassisColumnIndex = i;
                            break;
                        }
                    }
                    continue;
                }

                if (cols.length > chassisColumnIndex) {
                    String chassis = cols[chassisColumnIndex].trim().toUpperCase(Locale.getDefault());
                    if (!TextUtils.isEmpty(chassis)) {
                        result.add(chassis);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    // =====================================================================
    // ক্যামেরা সেটআপ
    // =====================================================================
    private void setupPinchToZoomAndFocus() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    CameraInfo cameraInfo = camera.getCameraInfo();
                    CameraControl cameraControl = camera.getCameraControl();
                    float currentZoomRatio = cameraInfo.getZoomState().getValue().getZoomRatio();
                    float delta = detector.getScaleFactor();
                    cameraControl.setZoomRatio(currentZoomRatio * delta);
                }
                return true;
            }
        });

        previewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPointFactory factory = previewView.getMeteringPointFactory();
                MeteringPoint point = factory.createPoint(event.getX(), event.getY());
                FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
                camera.getCameraControl().startFocusAndMetering(action);
            }
            return true;
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CODE);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "ক্যামেরা পারমিশন ছাড়া স্ক্যান করা যাবে না।", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void processImageProxy(ImageProxy imageProxy) {
        if (!isScannerOn || isWaitingForNextScan) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            textRecognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        extractValidChassis(visionText);
                        imageProxy.close();
                    })
                    .addOnFailureListener(e -> imageProxy.close());
        } else {
            imageProxy.close();
        }
    }

    // =====================================================================
    // OCR
    // =====================================================================
    private static final Pattern HYPHEN_CHASSIS_PATTERN = Pattern.compile("[A-Z0-9]{1,8}\\s*-\\s*[0-9]{3,9}");
    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern FRAME_KEYWORD_PATTERN = Pattern.compile("FRAME|CHASSIS|VIN|VEHICLE\\s*ID");

    private void extractValidChassis(com.google.mlkit.vision.text.Text visionText) {
        String candidate = findCandidateNearKeyword(visionText);
        if (candidate == null) {
            candidate = findCandidateAnywhere(visionText.getText());
        }
        if (candidate == null) return;

        candidate = correctCommonOcrErrors(candidate);
        String bestGuess = findClosestKnownChassis(candidate);
        String finalCandidate = (bestGuess != null) ? bestGuess : candidate;

        if (!scannedChassisSet.contains(finalCandidate)) {
            isWaitingForNextScan = true;
            showEditDialogAndConfirm(finalCandidate);
        }
    }

    private String findCandidateNearKeyword(com.google.mlkit.vision.text.Text visionText) {
        for (com.google.mlkit.vision.text.Text.TextBlock block : visionText.getTextBlocks()) {
            List<com.google.mlkit.vision.text.Text.Line> lines = block.getLines();
            for (int i = 0; i < lines.size(); i++) {
                String lineText = lines.get(i).getText().toUpperCase(Locale.getDefault());
                if (!FRAME_KEYWORD_PATTERN.matcher(lineText).find()) continue;

                String candidate = extractPatternFromText(lineText);
                if (candidate != null) return candidate;

                if (i + 1 < lines.size()) {
                    String nextLine = lines.get(i + 1).getText().toUpperCase(Locale.getDefault());
                    candidate = extractPatternFromText(nextLine);
                    if (candidate != null) return candidate;
                }
            }
        }
        return null;
    }

    private String findCandidateAnywhere(String rawText) {
        if (TextUtils.isEmpty(rawText)) return null;
        return extractPatternFromText(rawText.toUpperCase(Locale.getDefault()).replaceAll("\\n", " "));
    }

    private String extractPatternFromText(String text) {
        Matcher hyphenMatcher = HYPHEN_CHASSIS_PATTERN.matcher(text);
        if (hyphenMatcher.find()) {
            return hyphenMatcher.group().replaceAll("\\s+", "");
        }
        Matcher vinMatcher = VIN_PATTERN.matcher(text);
        if (vinMatcher.find()) {
            return vinMatcher.group();
        }
        return null;
    }

    private String correctCommonOcrErrors(String chassis) {
        if (chassis.contains("-")) {
            String[] parts = chassis.split("-");
            if (parts.length == 2) {
                String prefix = parts[0];
                String number = parts[1];
                if (prefix.matches("^(R70|R75|R71|70|75|71)$")) {
                    prefix = "ZRR" + prefix.replaceAll("[^0-9]", "");
                } else if (prefix.startsWith("RR") || prefix.startsWith("ZR")) {
                    prefix = prefix.replace("RRI", "ZRR").replace("RRO", "ZRR").replace("ZR", "ZRR").replace("ZRRR", "ZRR");
                }
                prefix = prefix.replace('O', '0').replace('I', '1');
                number = number.replace('O', '0').replace('I', '1');
                return prefix + "-" + number;
            }
        } else if (chassis.length() == 17) {
            chassis = chassis.replace('O', '0').replace('I', '1');
        }
        return chassis;
    }

    private String findClosestKnownChassis(String scanned) {
        if (TextUtils.isEmpty(scanned)) return null;
        if (stockSheetList.contains(scanned) || purchaseSheetList.contains(scanned)) {
            return scanned;
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        final int MAX_ALLOWED_DISTANCE = 2;

        for (String candidate : allSheetChassisForSuggestion) {
            if (Math.abs(candidate.length() - scanned.length()) > MAX_ALLOWED_DISTANCE) continue;
            int distance = levenshteinDistance(scanned, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        if (best != null && bestDistance <= MAX_ALLOWED_DISTANCE && bestDistance > 0) {
            return best;
        }
        return null;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) dp[i][j] = j;
                else if (j == 0) dp[i][j] = i;
                else {
                    int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                    dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
                }
            }
        }
        return dp[a.length()][b.length()];
    }

    private void showEditDialogAndConfirm(String scannedChassis) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Confirm or Edit Scanned Chassis");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setText(scannedChassis);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String finalChassis = input.getText().toString().trim().toUpperCase(Locale.getDefault());
            if (!TextUtils.isEmpty(finalChassis)) {
                processAndSaveChassis(finalChassis);
                Toast.makeText(this, "Saved: " + finalChassis, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> isWaitingForNextScan = false);
        builder.setCancelable(false);
        builder.show();
    }

    // =====================================================================
    // সেভ ও লোড
    // =====================================================================
    private void processAndSaveChassis(String chassis) {
        String location = etLocation.getText().toString().trim();
        if (TextUtils.isEmpty(location)) location = "218";

        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        ScannedModel model;
        if (stockSheetList.contains(chassis)) {
            model = new ScannedModel(chassis, location, timeStamp, "STOCK_MATCH");
            stockMatchList.add(model);
        } else if (purchaseSheetList.contains(chassis)) {
            model = new ScannedModel(chassis, location, timeStamp, "PURCHASE_MATCH");
            purchaseMatchList.add(model);
        } else {
            model = new ScannedModel(chassis, location, timeStamp, "UNKNOWN");
            unknownList.add(model);
        }

        scannedChassisSet.add(chassis);
        int total = stockMatchList.size() + purchaseMatchList.size() + unknownList.size();
        tvLastScanned.setText("Last: " + chassis + " (Total: " + total + ")");
        saveReportsToStorage();
    }

    private void saveReportsToStorage() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putStringSet("stock_match_list", serializeList(stockMatchList));
        editor.putStringSet("purchase_match_list", serializeList(purchaseMatchList));
        editor.putStringSet("unknown_list", serializeList(unknownList));
        editor.apply();
    }

    private Set<String> serializeList(List<ScannedModel> list) {
        Set<String> set = new HashSet<>();
        for (ScannedModel item : list) set.add(item.serialize());
        return set;
    }

    private void loadReportsFromStorage() {
        stockMatchList = loadListFromKey("stock_match_list");
        purchaseMatchList = loadListFromKey("purchase_match_list");
        unknownList = loadListFromKey("unknown_list");

        scannedChassisSet.clear();
        for (ScannedModel m : stockMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : purchaseMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : unknownList) scannedChassisSet.add(m.chassis);

        int total = stockMatchList.size() + purchaseMatchList.size() + unknownList.size();
        if (total > 0) {
            ScannedModel lastItem = !unknownList.isEmpty() ? unknownList.get(unknownList.size() - 1)
                    : !purchaseMatchList.isEmpty() ? purchaseMatchList.get(purchaseMatchList.size() - 1)
                      : stockMatchList.get(stockMatchList.size() - 1);
            tvLastScanned.setText("Last: " + lastItem.chassis + " (Total: " + total + ")");
        }
    }

    private List<ScannedModel> loadListFromKey(String key) {
        List<ScannedModel> list = new ArrayList<>();
        Set<String> set = sharedPreferences.getStringSet(key, null);
        if (set != null) {
            for (String raw : set) {
                ScannedModel m = ScannedModel.deserialize(raw);
                if (m != null) list.add(m);
            }
        }
        return list;
    }

    // =====================================================================
    // রিপোর্ট ডায়ালগ
    // =====================================================================
    private void showScannedSummaryDialog() {
        int total = stockMatchList.size() + purchaseMatchList.size() + unknownList.size();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Scanned Summary (" + total + ")");

        String[] options = {
                "Stock Matches (" + stockMatchList.size() + ")",
                "Purchase Matches (" + purchaseMatchList.size() + ")",
                "Unknown Items (" + unknownList.size() + ")"
        };

        builder.setItems(options, (dialog, which) -> {
            if (which == 0) showCategoryListDialog("Stock Matches", stockMatchList);
            else if (which == 1) showCategoryListDialog("Purchase Matches", purchaseMatchList);
            else showCategoryListDialog("Unknown Items", unknownList);
        });

        builder.setNegativeButton("Close", null);
        builder.setNeutralButton("Clear ALL Reports", (dialog, which) -> {
            new AlertDialog.Builder(this)
                    .setTitle("সব রিপোর্ট মুছে ফেলবেন?")
                    .setMessage("Stock, Purchase, Unknown — তিনটা লিস্টই একসাথে খালি হয়ে যাবে।")
                    .setPositiveButton("হ্যাঁ, সব মুছে দাও", (d2, w2) -> {
                        stockMatchList.clear();
                        purchaseMatchList.clear();
                        unknownList.clear();
                        rebuildScannedSet();
                        saveReportsToStorage();
                        tvLastScanned.setText("Last: None (Total: 0)");
                        Toast.makeText(this, "সব রিপোর্ট মুছে ফেলা হয়েছে!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("না", null)
                    .show();
        });
        builder.show();
    }

    private void showCategoryListDialog(String title, List<ScannedModel> list) {
        List<String> display = new ArrayList<>();
        for (ScannedModel m : list) {
            display.add(m.chassis + "  |  " + m.location + "  |  " + m.timestamp);
        }
        if (display.isEmpty()) display.add("(No items)");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title + " (আইটেমে ট্যাপ করলে Edit/Delete অপশন আসবে)");
        builder.setView(listView);
        builder.setNegativeButton("Close", null);
        builder.setPositiveButton("Clear All in " + title, (dialog, which) -> {
            list.clear();
            rebuildScannedSet();
            saveReportsToStorage();
            tvLastScanned.setText("Last: None (Total: " +
                    (stockMatchList.size() + purchaseMatchList.size() + unknownList.size()) + ")");
            Toast.makeText(this, title + " cleared!", Toast.LENGTH_SHORT).show();
        });
        AlertDialog dialog = builder.create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < list.size()) {
                dialog.dismiss();
                showEditOrDeleteChoice(title, list, position);
            }
        });
        dialog.show();
    }

    private void showEditOrDeleteChoice(String title, List<ScannedModel> list, int position) {
        ScannedModel item = list.get(position);
        String[] actions = {"Edit", "Delete", "Cancel"};

        new AlertDialog.Builder(this)
                .setTitle(item.chassis)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        showEditItemDialog(title, list, position);
                    } else if (which == 1) {
                        list.remove(position);
                        scannedChassisSet.remove(item.chassis);
                        saveReportsToStorage();
                        Toast.makeText(this, "Deleted: " + item.chassis, Toast.LENGTH_SHORT).show();
                        showCategoryListDialog(title, list);
                    } else {
                        showCategoryListDialog(title, list);
                    }
                })
                .show();
    }

    private void showEditItemDialog(String title, List<ScannedModel> list, int position) {
        ScannedModel item = list.get(position);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final EditText chassisInput = new EditText(this);
        chassisInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        chassisInput.setText(item.chassis);
        chassisInput.setHint("Chassis Number");

        final EditText locationInput = new EditText(this);
        locationInput.setText(item.location);
        locationInput.setHint("Location");

        layout.addView(chassisInput);
        layout.addView(locationInput);

        new AlertDialog.Builder(this)
                .setTitle("Edit Item")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newChassis = chassisInput.getText().toString().trim().toUpperCase(Locale.getDefault());
                    String newLocation = locationInput.getText().toString().trim();
                    if (TextUtils.isEmpty(newChassis)) {
                        Toast.makeText(this, "Chassis খালি রাখা যাবে না", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    list.remove(position);
                    scannedChassisSet.remove(item.chassis);

                    String newStatus;
                    if (stockSheetList.contains(newChassis)) {
                        newStatus = "STOCK_MATCH";
                        stockMatchList.add(new ScannedModel(newChassis, newLocation, item.timestamp, newStatus));
                    } else if (purchaseSheetList.contains(newChassis)) {
                        newStatus = "PURCHASE_MATCH";
                        purchaseMatchList.add(new ScannedModel(newChassis, newLocation, item.timestamp, newStatus));
                    } else {
                        newStatus = "UNKNOWN";
                        unknownList.add(new ScannedModel(newChassis, newLocation, item.timestamp, newStatus));
                    }
                    scannedChassisSet.add(newChassis);
                    saveReportsToStorage();
                    Toast.makeText(this, "Updated: " + newChassis + " (" + newStatus + ")", Toast.LENGTH_SHORT).show();
                    showCategoryListDialog(title, list);
                })
                .setNegativeButton("Cancel", (dialog, which) -> showCategoryListDialog(title, list))
                .show();
    }

    private void rebuildScannedSet() {
        scannedChassisSet.clear();
        for (ScannedModel m : stockMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : purchaseMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : unknownList) scannedChassisSet.add(m.chassis);
    }

    // =====================================================================
    // এক্সপোর্ট অপশন (নতুন Summary Report সহ)
    // =====================================================================
    private void showExportOptionsDialog() {
        String[] options = {
                "1. Location-wise Summary (PDF)",
                "2. Location-wise Summary (Excel/CSV)",
                "3. Stock Matches (CSV)",
                "4. Purchase Matches (CSV)",
                "5. Unknown Items (PDF)"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Export / Share Reports");
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) shareLocationSummaryPdf();
            else if (which == 1) shareLocationSummaryCsv();
            else if (which == 2) shareCsvReport(stockMatchList, "STOCK_MATCH");
            else if (which == 3) shareCsvReport(purchaseMatchList, "PURCHASE_MATCH");
            else sharePdfReport(unknownList, "UNKNOWN");
        });
        builder.show();
    }

    // Location অনুযায়ী গ্রুপ করে Summary বানানো
    private Map<String, Integer> buildLocationSummary() {
        Map<String, Integer> locationCount = new LinkedHashMap<>();

        // শুধু Stock Matches নিয়ে Summary (আপনার ছবি অনুযায়ী)
        for (ScannedModel m : stockMatchList) {
            String loc = m.location == null || m.location.trim().isEmpty() ? "Unknown" : m.location.trim();
            locationCount.put(loc, locationCount.getOrDefault(loc, 0) + 1);
        }
        return locationCount;
    }

    // ===== নতুন PDF Summary Report (ছবির মতো) =====
    private void shareLocationSummaryPdf() {
        Map<String, Integer> locationCount = buildLocationSummary();
        if (locationCount.isEmpty()) {
            Toast.makeText(this, "কোনো Stock Match ডাটা নেই!", Toast.LENGTH_SHORT).show();
            return;
        }

        PdfDocument pdfDocument = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = pdfDocument.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        Paint titlePaint = new Paint();
        titlePaint.setTextSize(18);
        titlePaint.setFakeBoldText(true);
        titlePaint.setColor(Color.BLACK);

        Paint headerPaint = new Paint();
        headerPaint.setTextSize(12);
        headerPaint.setFakeBoldText(true);
        headerPaint.setColor(Color.BLACK);

        Paint normalPaint = new Paint();
        normalPaint.setTextSize(11);
        normalPaint.setColor(Color.BLACK);

        Paint borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1);
        borderPaint.setColor(Color.BLACK);

        Paint yellowPaint = new Paint();
        yellowPaint.setColor(Color.YELLOW);
        yellowPaint.setStyle(Paint.Style.FILL);

        String dateStr = new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(new Date());
        canvas.drawText("STOCK LIST CHECKING REPORT , " + dateStr, 40, 50, titlePaint);

        int totalStock = 0;
        for (int qty : locationCount.values()) totalStock += qty;
        canvas.drawText("TOTAL STOCK (" + totalStock + ")", 40, 75, headerPaint);

        int startX = 40;
        int startY = 110;
        int rowHeight = 28;
        int[] colWidths = {60, 120, 200, 120}; // SL NO, QUANTITY, LOCATION, REMARKS

        // Header
        String[] headers = {"SL NO", "CAR QUANTITY", "CAR LOCATION", "REMARKS"};
        int currentX = startX;
        for (int i = 0; i < headers.length; i++) {
            canvas.drawRect(currentX, startY, currentX + colWidths[i], startY + rowHeight, borderPaint);
            canvas.drawText(headers[i], currentX + 5, startY + 19, headerPaint);
            currentX += colWidths[i];
        }
        startY += rowHeight;

        int sl = 1;
        for (Map.Entry<String, Integer> entry : locationCount.entrySet()) {
            String[] row = {
                    String.valueOf(sl++),
                    String.valueOf(entry.getValue()),
                    entry.getKey(),
                    ""
            };
            currentX = startX;
            for (int j = 0; j < row.length; j++) {
                canvas.drawRect(currentX, startY, currentX + colWidths[j], startY + rowHeight, borderPaint);
                canvas.drawText(row[j], currentX + 5, startY + 19, normalPaint);
                currentX += colWidths[j];
            }
            startY += rowHeight;

            if (startY > 750) {
                pdfDocument.finishPage(page);
                pageInfo = new PdfDocument.PageInfo.Builder(595, 842, pdfDocument.getPages().size() + 1).create();
                page = pdfDocument.startPage(pageInfo);
                canvas = page.getCanvas();
                startY = 40;
            }
        }

        // Total row (yellow)
        canvas.drawRect(startX, startY, startX + colWidths[0] + colWidths[1] + colWidths[2] + colWidths[3], startY + rowHeight, yellowPaint);
        currentX = startX;
        canvas.drawRect(currentX, startY, currentX + colWidths[0], startY + rowHeight, borderPaint);
        canvas.drawText("", currentX + 5, startY + 19, headerPaint);
        currentX += colWidths[0];
        canvas.drawRect(currentX, startY, currentX + colWidths[1], startY + rowHeight, borderPaint);
        canvas.drawText(String.valueOf(totalStock), currentX + 5, startY + 19, headerPaint);
        currentX += colWidths[1];
        canvas.drawRect(currentX, startY, currentX + colWidths[2], startY + rowHeight, borderPaint);
        currentX += colWidths[2];
        canvas.drawRect(currentX, startY, currentX + colWidths[3], startY + rowHeight, borderPaint);

        startY += 50;
        canvas.drawText("ADDED REPORT:", 40, startY, headerPaint);
        startY += 40;
        canvas.drawText("PREPARED BY", 40, startY, normalPaint);
        canvas.drawRect(40, startY + 10, 200, startY + 40, borderPaint);

        pdfDocument.finishPage(page);

        try {
            File cachePath = new File(getCacheDir(), "reports");
            cachePath.mkdirs();
            File file = new File(cachePath, "Stock_List_Checking_Report.pdf");
            pdfDocument.writeTo(new FileOutputStream(file));
            pdfDocument.close();
            openShareIntent(file, "application/pdf");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "PDF বানাতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show();
        }
    }

    // ===== নতুন Excel/CSV Summary Report =====
    private void shareLocationSummaryCsv() {
        Map<String, Integer> locationCount = buildLocationSummary();
        if (locationCount.isEmpty()) {
            Toast.makeText(this, "কোনো Stock Match ডাটা নেই!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File cachePath = new File(getCacheDir(), "reports");
            cachePath.mkdirs();
            File file = new File(cachePath, "Stock_List_Checking_Report.csv");

            FileWriter writer = new FileWriter(file);
            String dateStr = new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(new Date());
            writer.append("STOCK LIST CHECKING REPORT , ").append(dateStr).append("\n");

            int totalStock = 0;
            for (int qty : locationCount.values()) totalStock += qty;
            writer.append("TOTAL STOCK (").append(String.valueOf(totalStock)).append(")\n");
            writer.append("SL NO,CAR QUANTITY,CAR LOCATION,REMARKS\n");

            int sl = 1;
            for (Map.Entry<String, Integer> entry : locationCount.entrySet()) {
                writer.append(String.valueOf(sl++)).append(",")
                        .append(String.valueOf(entry.getValue())).append(",")
                        .append(entry.getKey()).append(",")
                        .append("\n");
            }
            writer.append(",").append(String.valueOf(totalStock)).append(",,\n");
            writer.append("\nADDED REPORT:\n");
            writer.append("PREPARED BY\n");

            writer.flush();
            writer.close();
            openShareIntent(file, "text/csv");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "CSV বানাতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show();
        }
    }

    // পুরনো ডিটেইল রিপোর্টগুলো
    private void sharePdfReport(List<ScannedModel> filtered, String filterStatus) {
        PdfDocument pdfDocument = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = pdfDocument.startPage(pageInfo);

        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();
        Paint borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1);
        borderPaint.setColor(Color.BLACK);

        paint.setTextSize(16);
        paint.setFakeBoldText(true);
        canvas.drawText("Scanned Items Report (" + filterStatus + ")", 40, 50, paint);

        paint.setTextSize(12);
        paint.setFakeBoldText(false);
        String currentDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        canvas.drawText("Generated Date: " + currentDate, 40, 75, paint);
        canvas.drawText("Total Count: " + filtered.size(), 40, 95, paint);

        int startX = 40;
        int startY = 130;
        int rowHeight = 30;
        int[] colWidths = {40, 110, 110, 80, 150};
        String[] headers = {"SR", "CHASIS", "STATUS", "LOCATION", "TIME STAMP"};

        paint.setFakeBoldText(true);
        int currentX = startX;
        for (int i = 0; i < headers.length; i++) {
            canvas.drawRect(currentX, startY, currentX + colWidths[i], startY + rowHeight, borderPaint);
            canvas.drawText(headers[i], currentX + 5, startY + 20, paint);
            currentX += colWidths[i];
        }

        paint.setFakeBoldText(false);
        startY += rowHeight;

        for (int i = 0; i < filtered.size(); i++) {
            ScannedModel item = filtered.get(i);
            String[] rowData = {
                    String.valueOf(i + 1), item.chassis, item.status, item.location, item.timestamp
            };
            currentX = startX;
            for (int j = 0; j < rowData.length; j++) {
                canvas.drawRect(currentX, startY, currentX + colWidths[j], startY + rowHeight, borderPaint);
                canvas.drawText(rowData[j], currentX + 5, startY + 20, paint);
                currentX += colWidths[j];
            }
            startY += rowHeight;

            if (startY > 780 && i != filtered.size() - 1) {
                pdfDocument.finishPage(page);
                PdfDocument.PageInfo newPageInfo = new PdfDocument.PageInfo.Builder(595, 842, pdfDocument.getPages().size() + 1).create();
                page = pdfDocument.startPage(newPageInfo);
                canvas = page.getCanvas();
                startY = 40;
            }
        }

        pdfDocument.finishPage(page);

        try {
            File cachePath = new File(getCacheDir(), "reports");
            cachePath.mkdirs();
            File file = new File(cachePath, filterStatus + "_Report.pdf");
            pdfDocument.writeTo(new FileOutputStream(file));
            pdfDocument.close();
            openShareIntent(file, "application/pdf");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to generate PDF", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareCsvReport(List<ScannedModel> filtered, String filterStatus) {
        try {
            File cachePath = new File(getCacheDir(), "reports");
            cachePath.mkdirs();
            File file = new File(cachePath, filterStatus + "_Report.csv");

            FileWriter writer = new FileWriter(file);
            writer.append("SR,CHASIS,STATUS,LOCATION,TIME STAMP\n");
            int count = 1;
            for (ScannedModel item : filtered) {
                writer.append(String.valueOf(count++)).append(",")
                        .append(item.chassis).append(",")
                        .append(item.status).append(",")
                        .append(item.location).append(",")
                        .append(item.timestamp).append("\n");
            }
            writer.flush();
            writer.close();
            openShareIntent(file, "text/csv");
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to generate CSV", Toast.LENGTH_SHORT).show();
        }
    }

    private void openShareIntent(File file, String mimeType) {
        Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Share Report Via"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        networkExecutor.shutdown();
    }
}