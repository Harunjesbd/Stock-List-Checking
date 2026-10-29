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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    // ================== ডিফল্ট Google Sheet CSV লিংক ==================
    // (অন্য কেউ এই অ্যাপ ব্যবহার করলে, Sync Settings থেকে এই লিংকগুলো বদলে
    // নিজের শিটের লিংক বসাতে পারবে — কোড পরিবর্তন করার প্রয়োজন নেই)
    private static final String STOCK_SHEET_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vR5RLl9fVBxpbJ-8O3BZllSRQy_1XV3tRJoqyofjThOMGBhAM1Ef6OuJ3rLr41AYAkK64a3KC6s5k_r/pub?gid=0&single=true&output=csv";
    private static final String PURCHASE_SHEET_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vQ9h4YoDux9zsT9Ig-GGgx360QNYBvjYeuoGY_PfSECKoh6yuar9kCdOkzG2xs61O5qNqVsrIAjFPt0/pub?gid=0&single=true&output=csv";
    private static final String CURRENT_MONTH_SALES_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vTPaPHujg4eVZVBkiXSQ8nuLiXgv2SU8Qu9h6jKG2pua5EKok5IRWPZyQfpKFk8DlGdKtqMtNg6NUvF/pub?gid=0&single=true&output=csv";
    private static final String ADVANCED_SALES_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vRDLo3Q7ba6Xl87L0Uv5Im4oqb4PQ-DK-qRSITnsPwOwi_KxTnpl8vdwh_TFhehMjM9jmCCThuiM8S_/pub?gid=0&single=true&output=csv";
    private static final String MAIN_SALES_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vT1LsaB64Qkv7cUjn_h3rZUEhBbVlaVhaaxPjsasdQh55jA9VNHYVgnvtzcFmtgWHpxPBhCL9nDTe1T/pub?gid=0&single=true&output=csv";

    // ================== Sync Settings এর জন্য SharedPreferences কী ==================
    private static final String PREF_SYNC_PURCHASE_ON = "sync_purchase_enabled";
    private static final String PREF_SYNC_SOLD_ON = "sync_sold_enabled";
    private static final String PREF_URL_STOCK = "custom_url_stock";
    private static final String PREF_URL_PURCHASE = "custom_url_purchase";
    private static final String PREF_URL_SOLD_CURRENT = "custom_url_sold_current";
    private static final String PREF_URL_SOLD_ADVANCED = "custom_url_sold_advanced";
    private static final String PREF_URL_SOLD_MAIN = "custom_url_sold_main";
    private static final String PREF_KNOWN_LOCATIONS = "known_locations";

    private static final int COLOR_SKY_BLUE = 0xFF87CEEB;

    private PreviewView previewView;
    private TextView tvLastScanned, tvStockCount;
    private AutoCompleteTextView etLocation;
    private AutoCompleteTextView etManualChassis;
    private Button btnAddManual, btnSyncSheets, btnViewReport, btnExportFiles, btnScannerStatus, btnNextScan;

    private TextRecognizer textRecognizer;
    private SharedPreferences sharedPreferences;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Set<String> scannedChassisSet = new HashSet<>();
    private List<ScannedModel> stockMatchList = new ArrayList<>();
    private List<ScannedModel> soldMatchList = new ArrayList<>();
    private List<ScannedModel> purchaseMatchList = new ArrayList<>();
    private List<ScannedModel> unknownList = new ArrayList<>();

    // Location অটো-সাজেশনের জন্য — আগে যা যা লোকেশন টাইপ করা হয়েছে, সেগুলোর লিস্ট
    private final List<String> knownLocations = new ArrayList<>();

    public static class SheetDetails {
        public String purchaseDate = "";
        public String vendor = "";

        public SheetDetails(String purchaseDate, String vendor) {
            this.purchaseDate = purchaseDate;
            this.vendor = vendor;
        }
    }

    private Map<String, SheetDetails> stockSheetMap = new LinkedHashMap<>();
    private Map<String, SheetDetails> purchaseSheetMap = new LinkedHashMap<>();
    private Map<String, SheetDetails> currentMonthSalesMap = new LinkedHashMap<>();
    private Map<String, SheetDetails> advancedSalesMap = new LinkedHashMap<>();
    private Map<String, SheetDetails> mainSalesMap = new LinkedHashMap<>();
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

        loadKnownLocations();
        setupManualEntryAutoSuggest();
        setupLocationAutoSuggest();
        loadReportsFromStorage();
        fetchSheetDataFromGoogle(true);
        checkPermissions();

        btnAddManual.setOnClickListener(v -> {
            String location = etLocation.getText().toString().trim();
            if (TextUtils.isEmpty(location)) {
                Toast.makeText(this, "আগে Location লিখুন!", Toast.LENGTH_SHORT).show();
                etLocation.requestFocus();
                return;
            }

            String chassis = etManualChassis.getText().toString().trim().toUpperCase(Locale.getDefault());
            if (!TextUtils.isEmpty(chassis)) {
                if (scannedChassisSet.contains(chassis)) {
                    Toast.makeText(this, "Duplicate Chassis Ignored!", Toast.LENGTH_SHORT).show();
                } else {
                    processAndSaveChassis(chassis);
                    etManualChassis.setText("");
                }
            } else {
                Toast.makeText(this, "Chassis Number লিখুন!", Toast.LENGTH_SHORT).show();
            }
        });

        btnScannerStatus.setOnClickListener(v -> {
            isScannerOn = !isScannerOn;
            btnScannerStatus.setText("Scanner Status: " + (isScannerOn ? "ON" : "OFF"));
            btnScannerStatus.setBackgroundColor(isScannerOn ? 0xFF2E7D32 : 0xFFC62828);
        });

        btnSyncSheets.setOnClickListener(v -> fetchSheetDataFromGoogle(false));
        // লম্বা চাপ (long-press) দিলে Sync Settings খুলবে — অন্য কেউ এই অ্যাপ ব্যবহার করলে
        // এখান থেকে নিজের Google Sheet লিংক বসাতে পারবে, এবং Purchase/Sold সিঙ্ক বন্ধ করতে পারবে
        btnSyncSheets.setOnLongClickListener(v -> {
            showSyncSettingsDialog();
            return true;
        });

        btnViewReport.setOnClickListener(v -> showScannedSummaryDialog());
        btnExportFiles.setOnClickListener(v -> showExportOptionsDialog());

        btnNextScan.setOnClickListener(v -> {
            isWaitingForNextScan = false;
            Toast.makeText(this, "Ready for next scan", Toast.LENGTH_SHORT).show();
        });

        setupPinchToZoomAndFocus();
    }

    // ================== Manual Chassis অটো-সাজেশন ==================

    private void setupManualEntryAutoSuggest() {
        SuggestionAdapter adapter = new SuggestionAdapter(this, allSheetChassisForSuggestion);
        etManualChassis.setAdapter(adapter);
        etManualChassis.setThreshold(1);
        etManualChassis.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !allSheetChassisForSuggestion.isEmpty()) etManualChassis.showDropDown();
        });
        etManualChassis.setOnClickListener(v -> {
            if (!allSheetChassisForSuggestion.isEmpty()) etManualChassis.showDropDown();
        });
    }

    private void refreshSuggestionAdapter() {
        SuggestionAdapter adapter = new SuggestionAdapter(this, allSheetChassisForSuggestion);
        etManualChassis.setAdapter(adapter);
    }

    // ================== Location অটো-সাজেশন (নতুন ফিচার #1) ==================

    private void setupLocationAutoSuggest() {
        SuggestionAdapter adapter = new SuggestionAdapter(this, knownLocations);
        etLocation.setAdapter(adapter);
        etLocation.setThreshold(1);
        etLocation.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !knownLocations.isEmpty()) etLocation.showDropDown();
        });
        etLocation.setOnClickListener(v -> {
            if (!knownLocations.isEmpty()) etLocation.showDropDown();
        });
    }

    private void refreshLocationSuggestionAdapter() {
        SuggestionAdapter adapter = new SuggestionAdapter(this, knownLocations);
        etLocation.setAdapter(adapter);
    }

    private void loadKnownLocations() {
        knownLocations.clear();
        Set<String> set = sharedPreferences.getStringSet(PREF_KNOWN_LOCATIONS, null);
        if (set != null) knownLocations.addAll(set);
    }

    private void saveKnownLocations() {
        sharedPreferences.edit().putStringSet(PREF_KNOWN_LOCATIONS, new HashSet<>(knownLocations)).apply();
    }

    /**
     * একই লোকেশন সবসময় একই কেসিং-এ (ছোট/বড় হাতের অক্ষর) সেভ হওয়ার জন্য।
     * যেমন প্রথমবার "Nasa" লিখলে সেটা সেভ থাকবে। পরে কেউ "nasa" বা "NASA" লিখলেও
     * এই ফাংশন খুঁজে বের করে পুরোনো "Nasa" ফিরিয়ে দেবে, যাতে রিপোর্টে দুইরকম
     * নাম (Nasa, nasa, NASA) আলাদা আলাদাভাবে না জমে।
     */
    private String normalizeAndRecordLocation(String typedLocation) {
        if (TextUtils.isEmpty(typedLocation)) return typedLocation;
        String trimmed = typedLocation.trim();

        for (String known : knownLocations) {
            if (known.equalsIgnoreCase(trimmed)) {
                return known; // আগের সেভ করা কেসিং ব্যবহার হবে
            }
        }

        // সম্পূর্ণ নতুন লোকেশন — যেভাবে লেখা হয়েছে সেভাবেই সেভ হবে (প্রথমবার)
        knownLocations.add(trimmed);
        saveKnownLocations();
        refreshLocationSuggestionAdapter();
        return trimmed;
    }

    // সাধারণ Suggestion Adapter — Chassis এবং Location দুটোর জন্যই ব্যবহার হয়
    private static class SuggestionAdapter extends ArrayAdapter<String> {
        private final List<String> originalList;
        private List<String> filteredList;

        public SuggestionAdapter(Context context, List<String> items) {
            super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(items));
            this.originalList = new ArrayList<>(items);
            this.filteredList = new ArrayList<>(items);
        }

        @Override public int getCount() { return filteredList.size(); }
        @Override public String getItem(int position) { return filteredList.get(position); }

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
                        for (String item : originalList) {
                            if (item.toUpperCase(Locale.getDefault()).contains(filterPattern)) {
                                suggestions.add(item);
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
                    if (results.count > 0) notifyDataSetChanged();
                    else notifyDataSetInvalidated();
                }
            };
        }
    }

    // ================== Google Sheet থেকে CSV ডাউনলোড ==================

    private String getEffectiveUrl(String prefKey, String defaultUrl) {
        String custom = sharedPreferences.getString(prefKey, "");
        return TextUtils.isEmpty(custom) ? defaultUrl : custom;
    }

    private void fetchSheetDataFromGoogle(boolean silent) {
        final boolean syncPurchase = sharedPreferences.getBoolean(PREF_SYNC_PURCHASE_ON, true);
        final boolean syncSold = sharedPreferences.getBoolean(PREF_SYNC_SOLD_ON, true);

        networkExecutor.execute(() -> {
            String stockUrl = getEffectiveUrl(PREF_URL_STOCK, STOCK_SHEET_CSV_URL);
            Map<String, SheetDetails> newStock = downloadChassisMapFromCsv(stockUrl);

            Map<String, SheetDetails> newPurchase = new LinkedHashMap<>();
            if (syncPurchase) {
                String purchaseUrl = getEffectiveUrl(PREF_URL_PURCHASE, PURCHASE_SHEET_CSV_URL);
                newPurchase = downloadChassisMapFromCsv(purchaseUrl);
            }

            Map<String, SheetDetails> newCurrentSold = new LinkedHashMap<>();
            Map<String, SheetDetails> newAdvancedSold = new LinkedHashMap<>();
            Map<String, SheetDetails> newMainSold = new LinkedHashMap<>();
            if (syncSold) {
                newCurrentSold = downloadChassisMapFromCsv(getEffectiveUrl(PREF_URL_SOLD_CURRENT, CURRENT_MONTH_SALES_CSV_URL));
                newAdvancedSold = downloadChassisMapFromCsv(getEffectiveUrl(PREF_URL_SOLD_ADVANCED, ADVANCED_SALES_CSV_URL));
                newMainSold = downloadChassisMapFromCsv(getEffectiveUrl(PREF_URL_SOLD_MAIN, MAIN_SALES_CSV_URL));
            }

            final Map<String, SheetDetails> finalPurchase = newPurchase;
            final Map<String, SheetDetails> finalCurrentSold = newCurrentSold;
            final Map<String, SheetDetails> finalAdvancedSold = newAdvancedSold;
            final Map<String, SheetDetails> finalMainSold = newMainSold;

            mainHandler.post(() -> {
                if (newStock.isEmpty() && finalPurchase.isEmpty() && finalCurrentSold.isEmpty()
                        && finalAdvancedSold.isEmpty() && finalMainSold.isEmpty()) {
                    Toast.makeText(this, "শিট সিঙ্ক ব্যর্থ। ইন্টারনেট চেক করুন।", Toast.LENGTH_LONG).show();
                    return;
                }
                stockSheetMap = newStock;
                purchaseSheetMap = finalPurchase;
                currentMonthSalesMap = finalCurrentSold;
                advancedSalesMap = finalAdvancedSold;
                mainSalesMap = finalMainSold;

                allSheetChassisForSuggestion.clear();
                allSheetChassisForSuggestion.addAll(stockSheetMap.keySet());
                allSheetChassisForSuggestion.addAll(purchaseSheetMap.keySet());
                allSheetChassisForSuggestion.addAll(currentMonthSalesMap.keySet());
                allSheetChassisForSuggestion.addAll(advancedSalesMap.keySet());
                allSheetChassisForSuggestion.addAll(mainSalesMap.keySet());
                refreshSuggestionAdapter();

                int soldTotal = currentMonthSalesMap.size() + advancedSalesMap.size() + mainSalesMap.size();
                tvStockCount.setText("Stock: " + stockSheetMap.size()
                        + " | Purchase: " + (syncPurchase ? String.valueOf(purchaseSheetMap.size()) : "OFF")
                        + " | Sold: " + (syncSold ? String.valueOf(soldTotal) : "OFF"));
                if (!silent) Toast.makeText(this, "Sheets Synced Successfully!", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private Map<String, SheetDetails> downloadChassisMapFromCsv(String csvUrl) {
        Map<String, SheetDetails> resultMap = new LinkedHashMap<>();
        if (TextUtils.isEmpty(csvUrl)) return resultMap;
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
            int chassisCol = 1;
            int dateCol = 2;
            int vendorCol = 3;

            while ((line = reader.readLine()) != null) {
                // পুরোনো বাগ: TextUtils.isEmpty(line.trim()) দিয়ে শুধু সম্পূর্ণ ফাঁকা লাইন ধরা যেত।
                // কিন্তু গুগল শিট export এর শুরুতে ",,,,," এর মতো একটা লাইন থাকে (কমা আছে কিন্তু
                // কোনো লেখা নেই) — trim() দিয়ে এটা ধরা পড়তো না, তাই সেটাকেই ভুলভাবে হেডার
                // ধরে নেওয়া হতো, এবং আসল হেডার ("CHASSIS NO, PURCHASE DATE, VENDOR...") ডেটা-রো
                // ভেবে স্কিপ হয়ে যেত — এই কারণেই PDF-এ Purchase Date আর Vendor ফাঁকা আসছিল।
                // isBlankCsvLine() এখন কমা/কোট/স্পেস সব বাদ দিয়ে আসলেই লাইনটা ফাঁকা কিনা দেখে।
                if (isBlankCsvLine(line)) continue;
                String[] cols = parseCsvLine(line);
                if (!headerSkipped) {
                    headerSkipped = true;
                    for (int i = 0; i < cols.length; i++) {
                        String colHeader = cols[i].toUpperCase(Locale.getDefault());
                        if (colHeader.contains("CHASSIS")) chassisCol = i;
                        else if (colHeader.contains("PURCHASE DATE") || colHeader.contains("DATE")) dateCol = i;
                        else if (colHeader.contains("VENDOR")) vendorCol = i;
                    }
                    continue;
                }
                if (cols.length > chassisCol) {
                    String chassis = cols[chassisCol].trim().toUpperCase(Locale.getDefault());
                    if (isHeaderOrInvalidString(chassis)) continue;

                    String date = (cols.length > dateCol) ? cols[dateCol].trim() : "";
                    String vendor = (cols.length > vendorCol) ? cols[vendorCol].trim() : "";

                    resultMap.put(chassis, new SheetDetails(date, vendor));
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (connection != null) connection.disconnect();
        }
        return resultMap;
    }

    /** একটা CSV লাইনে শুধু কমা/কোট/স্পেস ছাড়া আর কিছু না থাকলে (যেমন ",,,,,") সেটাকে ফাঁকা ধরা হয়। */
    private boolean isBlankCsvLine(String line) {
        if (line == null) return true;
        String stripped = line.replaceAll("[,\"\\s]", "");
        return stripped.isEmpty();
    }

    private String[] parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens.toArray(new String[0]);
    }

    private boolean isHeaderOrInvalidString(String text) {
        if (TextUtils.isEmpty(text)) return true;
        String clean = text.replaceAll("[^A-Z0-9]", "");
        return clean.equals("CHASSISNO") || clean.equals("CHASSIS") || clean.equals("VIN")
                || clean.equals("SNO") || clean.equals("PURCHASEDATE") || clean.equals("VENDOR");
    }

    // ================== Sync Settings ডায়ালগ (নতুন ফিচার #5 এর অংশ) ==================
    // btnSyncSheets-এ লম্বা চাপ দিলে এটা খোলে। অন্য কেউ এই অ্যাপ ব্যবহার করতে চাইলে
    // এখান থেকেই নিজের Google Sheet লিংক বসাতে পারবে, এবং Purchase/Sold সিঙ্ক বন্ধ/চালু
    // করতে পারবে (শুধু Stock ব্যবহার করতে চাইলে বাকিগুলো বন্ধ রাখলে ফোন স্লো হবে না)।
    private void showSyncSettingsDialog() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        scrollView.addView(layout);

        TextView note = new TextView(this);
        note.setText("এখানে ফাঁকা রাখলে ডিফল্ট লিংক ব্যবহার হবে। অন্য ব্যবসার জন্য সেটআপ করলে এখানে নিজের Google Sheet-এর 'Publish to web' CSV লিংক বসান।");
        note.setPadding(0, 0, 0, pad);
        layout.addView(note);

        CheckBox cbSyncPurchase = new CheckBox(this);
        cbSyncPurchase.setText("Purchase শিট সিঙ্ক করুন");
        cbSyncPurchase.setChecked(sharedPreferences.getBoolean(PREF_SYNC_PURCHASE_ON, true));
        layout.addView(cbSyncPurchase);

        CheckBox cbSyncSold = new CheckBox(this);
        cbSyncSold.setText("Sold (বিক্রি) শিট ৩টা সিঙ্ক করুন");
        cbSyncSold.setChecked(sharedPreferences.getBoolean(PREF_SYNC_SOLD_ON, true));
        layout.addView(cbSyncSold);

        TextView hint2 = new TextView(this);
        hint2.setText("\nশুধু Stock মিলানোর প্রয়োজন হলে উপরের দুইটা অফ (uncheck) করে দিন — ফোন স্লো/হ্যাং হবে না।\n");
        layout.addView(hint2);

        EditText urlStock = makeUrlInput("Stock Sheet CSV লিংক (খালি = ডিফল্ট)", PREF_URL_STOCK);
        EditText urlPurchase = makeUrlInput("Purchase Sheet CSV লিংক (খালি = ডিফল্ট)", PREF_URL_PURCHASE);
        EditText urlSoldCurrent = makeUrlInput("Current Month Sales CSV লিংক (খালি = ডিফল্ট)", PREF_URL_SOLD_CURRENT);
        EditText urlSoldAdvanced = makeUrlInput("Advanced Sales CSV লিংক (খালি = ডিফল্ট)", PREF_URL_SOLD_ADVANCED);
        EditText urlSoldMain = makeUrlInput("Main Sales CSV লিংক (খালি = ডিফল্ট)", PREF_URL_SOLD_MAIN);

        layout.addView(labelFor("Stock Sheet:"));
        layout.addView(urlStock);
        layout.addView(labelFor("Purchase Sheet:"));
        layout.addView(urlPurchase);
        layout.addView(labelFor("Current Month Sales Sheet:"));
        layout.addView(urlSoldCurrent);
        layout.addView(labelFor("Advanced Sales Sheet:"));
        layout.addView(urlSoldAdvanced);
        layout.addView(labelFor("Main Sales Sheet:"));
        layout.addView(urlSoldMain);

        new AlertDialog.Builder(this)
                .setTitle("Sync Settings")
                .setView(scrollView)
                .setPositiveButton("Save & Sync", (dialog, which) -> {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putBoolean(PREF_SYNC_PURCHASE_ON, cbSyncPurchase.isChecked());
                    editor.putBoolean(PREF_SYNC_SOLD_ON, cbSyncSold.isChecked());
                    editor.putString(PREF_URL_STOCK, urlStock.getText().toString().trim());
                    editor.putString(PREF_URL_PURCHASE, urlPurchase.getText().toString().trim());
                    editor.putString(PREF_URL_SOLD_CURRENT, urlSoldCurrent.getText().toString().trim());
                    editor.putString(PREF_URL_SOLD_ADVANCED, urlSoldAdvanced.getText().toString().trim());
                    editor.putString(PREF_URL_SOLD_MAIN, urlSoldMain.getText().toString().trim());
                    editor.apply();
                    Toast.makeText(this, "Settings সেভ হয়েছে। সিঙ্ক শুরু হচ্ছে...", Toast.LENGTH_SHORT).show();
                    fetchSheetDataFromGoogle(false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView labelFor(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private EditText makeUrlInput(String hint, String prefKey) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(sharedPreferences.getString(prefKey, ""));
        return et;
    }

    // ================== ক্যামেরা / জুম / ফোকাস ==================

    private void setupPinchToZoomAndFocus() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    float currentZoomRatio = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                    camera.getCameraControl().setZoomRatio(currentZoomRatio * detector.getScaleFactor());
                }
                return true;
            }
        });

        previewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPointFactory factory = previewView.getMeteringPointFactory();
                MeteringPoint point = factory.createPoint(event.getX(), event.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point).build());
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
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
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

    private static final Pattern HYPHEN_CHASSIS_PATTERN = Pattern.compile("[A-Z0-9]{1,8}\\s*-\\s*[0-9]{3,9}");
    private static final Pattern VIN_PATTERN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern FRAME_KEYWORD_PATTERN = Pattern.compile("FRAME|CHASSIS|VIN|VEHICLE\\s*ID");

    private void extractValidChassis(com.google.mlkit.vision.text.Text visionText) {
        String candidate = findCandidateNearKeyword(visionText);
        if (candidate == null) candidate = findCandidateAnywhere(visionText.getText());
        if (candidate == null) return;

        candidate = correctCommonOcrErrors(candidate);
        if (isHeaderOrInvalidString(candidate)) return;

        String bestGuess = findClosestKnownChassis(candidate);
        String finalCandidate = (bestGuess != null) ? bestGuess : candidate;

        if (!scannedChassisSet.contains(finalCandidate)) {
            String location = etLocation.getText().toString().trim();
            if (TextUtils.isEmpty(location)) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "আগে Location লিখুন! স্ক্যান বন্ধ আছে।", Toast.LENGTH_SHORT).show());
                return;
            }
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
                    candidate = extractPatternFromText(lines.get(i + 1).getText().toUpperCase(Locale.getDefault()));
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
        if (hyphenMatcher.find()) return hyphenMatcher.group().replaceAll("\\s+", "");
        Matcher vinMatcher = VIN_PATTERN.matcher(text);
        if (vinMatcher.find()) return vinMatcher.group();
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
        if (stockSheetMap.containsKey(scanned) || purchaseSheetMap.containsKey(scanned)
                || currentMonthSalesMap.containsKey(scanned) || advancedSalesMap.containsKey(scanned)
                || mainSalesMap.containsKey(scanned)) return scanned;

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
        if (best != null && bestDistance <= MAX_ALLOWED_DISTANCE && bestDistance > 0) return best;
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

    // ================== মূল লজিক: Stock → Sold → Purchase → Unknown (নতুন ফিচার #5) ==================
    private void processAndSaveChassis(String chassis) {
        String rawLocation = etLocation.getText().toString().trim();
        String location = normalizeAndRecordLocation(rawLocation);
        if (TextUtils.isEmpty(location)) location = "218";
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        ScannedModel model;
        if (stockSheetMap.containsKey(chassis)) {
            model = new ScannedModel(chassis, location, timeStamp, "STOCK_MATCH");
            stockMatchList.add(model);
        } else if (currentMonthSalesMap.containsKey(chassis) || advancedSalesMap.containsKey(chassis)
                || mainSalesMap.containsKey(chassis)) {
            model = new ScannedModel(chassis, location, timeStamp, "SOLD_MATCH");
            soldMatchList.add(model);
        } else if (purchaseSheetMap.containsKey(chassis)) {
            model = new ScannedModel(chassis, location, timeStamp, "PURCHASE_MATCH");
            purchaseMatchList.add(model);
        } else {
            model = new ScannedModel(chassis, location, timeStamp, "UNKNOWN");
            unknownList.add(model);
        }
        scannedChassisSet.add(chassis);
        int total = stockMatchList.size() + soldMatchList.size() + purchaseMatchList.size() + unknownList.size();
        tvLastScanned.setText("Last: " + chassis + " (Total: " + total + ")");
        saveReportsToStorage();
    }

    private void saveReportsToStorage() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putStringSet("stock_match_list", serializeList(stockMatchList));
        editor.putStringSet("sold_match_list", serializeList(soldMatchList));
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
        soldMatchList = loadListFromKey("sold_match_list");
        purchaseMatchList = loadListFromKey("purchase_match_list");
        unknownList = loadListFromKey("unknown_list");
        rebuildScannedSet();
        int total = stockMatchList.size() + soldMatchList.size() + purchaseMatchList.size() + unknownList.size();
        if (total > 0) {
            ScannedModel lastItem = !unknownList.isEmpty() ? unknownList.get(unknownList.size() - 1)
                    : !purchaseMatchList.isEmpty() ? purchaseMatchList.get(purchaseMatchList.size() - 1)
                      : !soldMatchList.isEmpty() ? soldMatchList.get(soldMatchList.size() - 1)
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

    private void rebuildScannedSet() {
        scannedChassisSet.clear();
        for (ScannedModel m : stockMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : soldMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : purchaseMatchList) scannedChassisSet.add(m.chassis);
        for (ScannedModel m : unknownList) scannedChassisSet.add(m.chassis);
    }

    // ================== রিপোর্ট দেখার ডায়ালগ ==================
    // নতুন ফিচার #2: "Clear ALL Reports" এখন সরাসরি দেখা যায় না — "⋮ More" বাটনের
    // ভেতরে hidden মেনুতে রাখা হয়েছে, যাতে ভুলে চাপ পড়ে সব ডিলিট না হয়ে যায়।
    private void showScannedSummaryDialog() {
        int total = stockMatchList.size() + soldMatchList.size() + purchaseMatchList.size() + unknownList.size();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Scanned Summary (" + total + ")");
        String[] options = {
                "Stock Matches (" + stockMatchList.size() + ")",
                "Sold Matches (" + soldMatchList.size() + ")",
                "Purchase Matches (" + purchaseMatchList.size() + ")",
                "Unknown Items (" + unknownList.size() + ")"
        };
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) showCategoryListDialog("Stock Matches", stockMatchList);
            else if (which == 1) showCategoryListDialog("Sold Matches", soldMatchList);
            else if (which == 2) showCategoryListDialog("Purchase Matches", purchaseMatchList);
            else showCategoryListDialog("Unknown Items", unknownList);
        });
        builder.setNegativeButton("Close", null);
        builder.setNeutralButton("⋮ More", (dialog, which) -> showSummaryMoreOptionsMenu());
        builder.show();
    }

    private void showSummaryMoreOptionsMenu() {
        String[] menuItems = {"Clear ALL Reports"};
        new AlertDialog.Builder(this)
                .setTitle("More Options")
                .setItems(menuItems, (dialog, which) -> {
                    if (which == 0) confirmClearAllReports();
                })
                .setNegativeButton("Back", (dialog, which) -> showScannedSummaryDialog())
                .show();
    }

    private void confirmClearAllReports() {
        new AlertDialog.Builder(this)
                .setTitle("সব রিপোর্ট মুছে ফেলবেন?")
                .setMessage("Stock, Sold, Purchase, Unknown — চারটা লিস্টই একসাথে খালি হয়ে যাবে।")
                .setPositiveButton("YES", (d2, w2) -> {
                    stockMatchList.clear();
                    soldMatchList.clear();
                    purchaseMatchList.clear();
                    unknownList.clear();
                    rebuildScannedSet();
                    saveReportsToStorage();
                    tvLastScanned.setText("Last: None (Total: 0)");
                    Toast.makeText(this, "সব রিপোর্ট মুছে ফেলা হয়েছে!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("NO", null).show();
    }

    // নতুন ফিচার #3: "Clear All in [Category]" বাটনও এখন সরাসরি দেখা যায় না, hidden
    // মেনুতে রাখা হয়েছে। চেসিস সার্চ ফিল্টার আগে থেকেই আছে (এখনো আছে)। হেডারে sky-blue
    // "frozen pane" স্টাইল যোগ করা হয়েছে।
    private void showCategoryListDialog(String title, List<ScannedModel> list) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        // Parent container view
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(0, 0, 0, pad);

        // ---- "Frozen pane" sky-blue header (টাইটেল + সার্চ বক্স, স্ক্রল করলেও সবসময় উপরে দেখা যাবে) ----
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.VERTICAL);
        headerLayout.setBackgroundColor(COLOR_SKY_BLUE);
        headerLayout.setPadding(pad, pad, pad, pad);
        headerLayout.setElevation(8f);

        TextView titleTextView = new TextView(this);
        titleTextView.setText(title + " (আইটেমে ট্যাপ করলে Edit/Delete অপশন আসবে)");
        titleTextView.setTextSize(15);
        titleTextView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTextView.setTextColor(Color.BLACK);
        titleTextView.setPadding(0, 0, 0, 8);

        EditText searchBox = new EditText(this);
        searchBox.setHint("Chassis Search...");
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        searchBox.setBackgroundColor(Color.WHITE);

        headerLayout.addView(titleTextView);
        headerLayout.addView(searchBox);
        dialogLayout.addView(headerLayout);

        // Dynamic filtered mapping
        List<ScannedModel> currentDisplayList = new ArrayList<>(list);
        List<String> displayStrings = new ArrayList<>();
        for (ScannedModel m : currentDisplayList) {
            displayStrings.add(m.chassis + "  |  " + m.location + "  |  " + m.timestamp);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayStrings);
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setPadding(pad, pad, pad, 0);
        dialogLayout.addView(listView);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogLayout);
        builder.setNegativeButton("Close", null);

        // "Clear All in [title]" এখন সরাসরি দেখা যায় না — "⋮ More" এর hidden মেনুর ভেতরে
        builder.setNeutralButton("⋮ More", (dialog, which) -> showCategoryMoreOptionsMenu(title, list));

        AlertDialog dialog = builder.create();

        // LIVE SEARCH LOGIC
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String filterPattern = s.toString().trim().toUpperCase(Locale.getDefault());
                currentDisplayList.clear();
                displayStrings.clear();

                for (ScannedModel item : list) {
                    if (item.chassis.toUpperCase(Locale.getDefault()).contains(filterPattern)) {
                        currentDisplayList.add(item);
                        displayStrings.add(item.chassis + "  |  " + item.location + "  |  " + item.timestamp);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        // EDIT/DELETE SELECTION ON ITEM CLICK
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < currentDisplayList.size()) {
                dialog.dismiss();
                ScannedModel selectedItem = currentDisplayList.get(position);
                int actualIndex = list.indexOf(selectedItem);
                if (actualIndex != -1) {
                    showEditOrDeleteChoice(title, list, actualIndex);
                }
            }
        });

        dialog.show();
    }

    private void showCategoryMoreOptionsMenu(String title, List<ScannedModel> list) {
        String[] menuItems = {"Clear All in " + title};
        new AlertDialog.Builder(this)
                .setTitle("More Options")
                .setItems(menuItems, (dialog, which) -> {
                    if (which == 0) confirmClearCategory(title, list);
                })
                .setNegativeButton("Back", (dialog, which) -> showCategoryListDialog(title, list))
                .show();
    }

    private void confirmClearCategory(String title, List<ScannedModel> list) {
        new AlertDialog.Builder(this)
                .setTitle("Are you sure?")
                .setMessage("Do you really want to clear all items in " + title + "?")
                .setPositiveButton("YES", (confirmDialog, confirmWhich) -> {
                    list.clear();
                    rebuildScannedSet();
                    saveReportsToStorage();
                    int total = stockMatchList.size() + soldMatchList.size() + purchaseMatchList.size() + unknownList.size();
                    tvLastScanned.setText("Last: None (Total: " + total + ")");
                    Toast.makeText(this, title + " cleared!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("NO", (confirmDialog, confirmWhich) -> showCategoryListDialog(title, list))
                .setCancelable(false)
                .show();
    }

    private void showEditOrDeleteChoice(String title, List<ScannedModel> list, int position) {
        ScannedModel item = list.get(position);
        String[] actions = {"Edit", "Delete", "Cancel"};
        new AlertDialog.Builder(this).setTitle(item.chassis)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showEditItemDialog(title, list, position);
                    else if (which == 1) {
                        list.remove(position);
                        rebuildScannedSet();
                        saveReportsToStorage();
                        Toast.makeText(this, "Deleted: " + item.chassis, Toast.LENGTH_SHORT).show();
                        showCategoryListDialog(title, list);
                    } else showCategoryListDialog(title, list);
                }).show();
    }

    private void showEditItemDialog(String title, List<ScannedModel> list, int position) {
        ScannedModel item = list.get(position);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
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
                    String newLocationRaw = locationInput.getText().toString().trim();
                    if (!TextUtils.isEmpty(newChassis)) {
                        String newLocation = normalizeAndRecordLocation(newLocationRaw);
                        item.chassis = newChassis;
                        item.location = newLocation;
                        rebuildScannedSet();
                        saveReportsToStorage();
                        Toast.makeText(this, "Updated successfully!", Toast.LENGTH_SHORT).show();
                    }
                    showCategoryListDialog(title, list);
                })
                .setNegativeButton("Cancel", (dialog, which) -> showCategoryListDialog(title, list))
                .show();
    }

    // ================== PDF/Excel Export ==================
    // প্রথমে কোন রিপোর্ট চান সেটা জিজ্ঞেস করে, তারপর PDF নাকি Excel (CSV) ফরম্যাটে
    // চান সেটা জিজ্ঞেস করে — প্রতিটা রিপোর্টের জন্যই দুটো ফরম্যাট-ই পাওয়া যাবে।
    private static final String[] EXPORT_REPORT_NAMES = {
            "Stock List Checking Summary Report (by Location)",
            "Stock Unmatch Remaining Balance Report",
            "All Scanned Stock Matches",
            "All Scanned Sold Matches",
            "All Scanned Purchase Matches",
            "All Scanned Unknown Items"
    };

    private void showExportOptionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Export Reports")
                .setItems(EXPORT_REPORT_NAMES, (dialog, which) -> showExportFormatDialog(which))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showExportFormatDialog(int reportIndex) {
        String[] formats = {"PDF", "Excel (CSV)"};
        new AlertDialog.Builder(this)
                .setTitle(EXPORT_REPORT_NAMES[reportIndex])
                .setItems(formats, (dialog, which) -> {
                    boolean asExcel = (which == 1);
                    generateSelectedReport(reportIndex, asExcel);
                })
                .setNegativeButton("Back", (dialog, which) -> showExportOptionsDialog())
                .show();
    }

    private void generateSelectedReport(int reportIndex, boolean asExcel) {
        switch (reportIndex) {
            case 0:
                if (asExcel) generateStockListCheckingSummaryCsv();
                else generateStockListCheckingSummaryPdf();
                break;
            case 1:
                if (asExcel) generateStockUnmatchRemainingCsv();
                else generateStockUnmatchRemainingPdf();
                break;
            case 2:
                if (asExcel) generateGenericReportCsv("Stock_Matches_Report", stockMatchList);
                else generateGenericReportPdf("Stock Matches Report", stockMatchList);
                break;
            case 3:
                if (asExcel) generateGenericReportCsv("Sold_Matches_Report", soldMatchList);
                else generateGenericReportPdf("Sold Matches Report", soldMatchList);
                break;
            case 4:
                if (asExcel) generateGenericReportCsv("Purchase_Matches_Report", purchaseMatchList);
                else generateGenericReportPdf("Purchase Matches Report", purchaseMatchList);
                break;
            case 5:
                if (asExcel) generateGenericReportCsv("Unknown_Items_Report", unknownList);
                else generateGenericReportPdf("Unknown Items Report", unknownList);
                break;
        }
    }

    /** CSV এর কোনো মান-এ কমা/কোট/নতুন-লাইন থাকলে সেটাকে ঠিকভাবে quote করে। */
    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * ছবিতে দেখানো "STOCK LIST CHECKING SUMMARY REPORT" স্টাইলের PDF — শুধু Stock Matches
     * (Purchase/Sold/Unknown বাদে) লোকেশন অনুযায়ী গ্রুপ করে কয়টা গাড়ি কোন লোকেশনে আছে
     * তার হিসাব দেখায়। হেডার রো sky-blue ফিল, ফুটার/টোটাল রো কোনো ফিল ছাড়া (no fill),
     * সব সেলের চারপাশে বর্ডার, এবং নিচে বাম পাশে "Prepared by:" লাইন।
     */
    private void generateStockListCheckingSummaryPdf() {
        // শুধু Stock Matches (not Purchase/Sold/Unknown) লোকেশন অনুযায়ী গ্রুপ করা হয়
        List<ScannedModel> allScanned = new ArrayList<>(stockMatchList);

        Map<String, Integer> locationCounts = new LinkedHashMap<>();
        for (ScannedModel m : allScanned) {
            String loc = TextUtils.isEmpty(m.location) ? "Unknown" : m.location;
            Integer existing = locationCounts.get(loc);
            locationCounts.put(loc, existing == null ? 1 : existing + 1);
        }
        int totalCars = allScanned.size();

        PdfDocument pdfDocument = new PdfDocument();
        int pageWidth = 595;
        int pageHeight = 842;
        int margin = 30;
        int rowHeight = 24;
        int currentPage = 1;

        int col1 = margin;              // SL NO
        int col1w = 50;
        int col2 = col1 + col1w;        // CAR QUANTITY
        int col2w = 110;
        int col3 = col2 + col2w;        // CAR LOCATION
        int col3w = 240;
        int col4 = col3 + col3w;        // REMARKS
        int col4w = pageWidth - margin - col4;
        int tableRight = col4 + col4w;

        Paint borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setColor(Color.BLACK);

        Paint headerFillPaint = new Paint();
        headerFillPaint.setStyle(Paint.Style.FILL);
        headerFillPaint.setColor(COLOR_SKY_BLUE);

        Paint cellTextPaint = new Paint();
        cellTextPaint.setTextSize(10);
        cellTextPaint.setColor(Color.BLACK);

        Paint headerTextPaint = new Paint();
        headerTextPaint.setTextSize(10);
        headerTextPaint.setFakeBoldText(true);
        headerTextPaint.setColor(Color.BLACK);

        Paint titlePaint = new Paint();
        titlePaint.setTextSize(16);
        titlePaint.setFakeBoldText(true);
        titlePaint.setColor(Color.BLACK);

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
        PdfDocument.Page page = pdfDocument.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        String todayStr = new SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(new Date());
        canvas.drawText("STOCK LIST CHECKING SUMMARY REPORT, " + todayStr, margin, 50, titlePaint);

        Paint subTitlePaint = new Paint();
        subTitlePaint.setTextSize(12);
        subTitlePaint.setColor(Color.BLACK);
        canvas.drawText("TOTAL STOCK (" + totalCars + ")", margin, 70, subTitlePaint);

        int currentY = 90;

        // হেডার রো আঁকার জন্য ছোট helper (নতুন পেজ শুরু হলে আবার লাগবে)
        currentY = drawCheckingReportHeaderRow(canvas, currentY, rowHeight, col1, col1w, col2, col2w,
                col3, col3w, col4, col4w, tableRight, headerFillPaint, borderPaint, headerTextPaint);

        int sl = 1;
        int pageBottomLimit = pageHeight - margin - 90;

        for (Map.Entry<String, Integer> entry : locationCounts.entrySet()) {
            if (currentY > pageBottomLimit) {
                pdfDocument.finishPage(page);
                currentPage++;
                pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
                page = pdfDocument.startPage(pageInfo);
                canvas = page.getCanvas();
                currentY = margin + 20;
                currentY = drawCheckingReportHeaderRow(canvas, currentY, rowHeight, col1, col1w, col2, col2w,
                        col3, col3w, col4, col4w, tableRight, headerFillPaint, borderPaint, headerTextPaint);
            }

            canvas.drawRect(col1, currentY, col1 + col1w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col2, currentY, col2 + col2w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col3, currentY, col3 + col3w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col4, currentY, col4 + col4w, currentY + rowHeight, borderPaint);

            canvas.drawText(String.valueOf(sl), col1 + 5, currentY + 16, cellTextPaint);
            canvas.drawText(String.valueOf(entry.getValue()), col2 + 5, currentY + 16, cellTextPaint);
            canvas.drawText(entry.getKey(), col3 + 5, currentY + 16, cellTextPaint);
            canvas.drawText("", col4 + 5, currentY + 16, cellTextPaint);

            currentY += rowHeight;
            sl++;
        }

        // ফুটার/টোটাল রো — কোনো ফিল কালার নেই (no fill), শুধু বর্ডার
        if (currentY > pageBottomLimit) {
            pdfDocument.finishPage(page);
            currentPage++;
            pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
            page = pdfDocument.startPage(pageInfo);
            canvas = page.getCanvas();
            currentY = margin + 20;
        }
        canvas.drawRect(col1, currentY, col1 + col1w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col2, currentY, col2 + col2w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col3, currentY, col3 + col3w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col4, currentY, col4 + col4w, currentY + rowHeight, borderPaint);
        canvas.drawText(String.valueOf(totalCars), col2 + 5, currentY + 16, headerTextPaint);
        currentY += rowHeight + 40;

        // নিচে বাম পাশে "Prepared by:" লাইন
        if (currentY > pageHeight - margin) {
            pdfDocument.finishPage(page);
            currentPage++;
            pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
            page = pdfDocument.startPage(pageInfo);
            canvas = page.getCanvas();
            currentY = margin + 20;
        }
        Paint preparedPaint = new Paint();
        preparedPaint.setTextSize(11);
        preparedPaint.setColor(Color.BLACK);
        canvas.drawText("Prepared by: ________________________________", margin, currentY, preparedPaint);

        pdfDocument.finishPage(page);
        saveAndOpenPdf(pdfDocument, "Stock_List_Checking_Summary_Report.pdf");
    }

    /** Stock List Checking Summary Report-এর Excel (CSV) ভার্সন — PDF-এর মতোই ডেটা, শুধু স্প্রেডশিট ফরম্যাটে। */
    private void generateStockListCheckingSummaryCsv() {
        // শুধু Stock Matches (not Purchase/Sold/Unknown) লোকেশন অনুযায়ী গ্রুপ করা হয়
        List<ScannedModel> allScanned = new ArrayList<>(stockMatchList);

        Map<String, Integer> locationCounts = new LinkedHashMap<>();
        for (ScannedModel m : allScanned) {
            String loc = TextUtils.isEmpty(m.location) ? "Unknown" : m.location;
            Integer existing = locationCounts.get(loc);
            locationCounts.put(loc, existing == null ? 1 : existing + 1);
        }
        int totalCars = allScanned.size();

        StringBuilder sb = new StringBuilder();
        sb.append("SL NO,CAR QUANTITY,CAR LOCATION,REMARKS\n");
        int sl = 1;
        for (Map.Entry<String, Integer> entry : locationCounts.entrySet()) {
            sb.append(sl).append(",").append(entry.getValue()).append(",")
                    .append(csvEscape(entry.getKey())).append(",\n");
            sl++;
        }
        sb.append(",").append(totalCars).append(",TOTAL,\n");
        sb.append("\nPrepared by:,,,\n");

        saveCsvAndOffer(sb.toString(), "Stock_List_Checking_Summary_Report.csv");
    }

    private int drawCheckingReportHeaderRow(Canvas canvas, int currentY, int rowHeight,
                                            int col1, int col1w, int col2, int col2w,
                                            int col3, int col3w, int col4, int col4w, int tableRight,
                                            Paint headerFillPaint, Paint borderPaint, Paint headerTextPaint) {
        canvas.drawRect(col1, currentY, tableRight, currentY + rowHeight, headerFillPaint);
        canvas.drawRect(col1, currentY, col1 + col1w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col2, currentY, col2 + col2w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col3, currentY, col3 + col3w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col4, currentY, col4 + col4w, currentY + rowHeight, borderPaint);
        canvas.drawText("SL NO", col1 + 5, currentY + 16, headerTextPaint);
        canvas.drawText("CAR QUANTITY", col2 + 5, currentY + 16, headerTextPaint);
        canvas.drawText("CAR LOCATION", col3 + 5, currentY + 16, headerTextPaint);
        canvas.drawText("REMARKS", col4 + 5, currentY + 16, headerTextPaint);
        return currentY + rowHeight;
    }

    private void generateStockUnmatchRemainingPdf() {
        List<String> unmatchRemaining = new ArrayList<>();
        for (String chassis : stockSheetMap.keySet()) {
            if (isHeaderOrInvalidString(chassis)) continue;
            if (!scannedChassisSet.contains(chassis)) {
                unmatchRemaining.add(chassis);
            }
        }

        PdfDocument pdfDocument = new PdfDocument();

        int pageWidth = 595;
        int pageHeight = 842;
        int margin = 30;
        int startY = 60;
        int rowHeight = 22;

        int totalItems = unmatchRemaining.size();
        int currentPage = 1;

        // কলাম — হেডার থেকে একদম নিচের রো পর্যন্ত সব বর্ডার আঁকার জন্য
        int col1 = margin;                 // S.NO
        int col1w = 40;
        int col2 = col1 + col1w;           // CHASSIS NO
        int col2w = 160;
        int col3 = col2 + col2w;           // PURCHASE DATE
        int col3w = 110;
        int col4 = col3 + col3w;           // VENDOR
        int col4w = 110;
        int col5 = col4 + col4w;           // REMARKS
        int col5w = (pageWidth - margin) - col5;
        int tableRight = col5 + col5w;

        Paint borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setColor(Color.BLACK);

        Paint headerFillPaint = new Paint();
        headerFillPaint.setStyle(Paint.Style.FILL);
        headerFillPaint.setColor(COLOR_SKY_BLUE);

        Paint cellTextPaint = new Paint();
        cellTextPaint.setTextSize(9);
        cellTextPaint.setColor(Color.BLACK);

        Paint headerTextPaint = new Paint();
        headerTextPaint.setTextSize(9);
        headerTextPaint.setFakeBoldText(true);
        headerTextPaint.setColor(Color.BLACK);

        Paint titlePaint = new Paint();
        titlePaint.setTextSize(16);
        titlePaint.setFakeBoldText(true);
        titlePaint.setColor(Color.BLACK);

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
        PdfDocument.Page page = pdfDocument.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        canvas.drawText("STOCK UNMATCH REMAINING BALANCE REPORT", margin, startY, titlePaint);

        Paint subTitlePaint = new Paint();
        subTitlePaint.setTextSize(12);
        subTitlePaint.setColor(Color.BLACK);
        canvas.drawText("Total Remaining: " + totalItems, margin, startY + 20, subTitlePaint);

        int currentY = startY + 40;

        currentY = drawUnmatchReportHeaderRow(canvas, currentY, rowHeight, col1, col1w, col2, col2w,
                col3, col3w, col4, col4w, col5, col5w, tableRight, headerFillPaint, borderPaint, headerTextPaint);

        for (int i = 0; i < unmatchRemaining.size(); i++) {
            if (currentY > pageHeight - margin - 20) {
                pdfDocument.finishPage(page);
                currentPage++;
                pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
                page = pdfDocument.startPage(pageInfo);
                canvas = page.getCanvas();
                currentY = margin + 20;
                currentY = drawUnmatchReportHeaderRow(canvas, currentY, rowHeight, col1, col1w, col2, col2w,
                        col3, col3w, col4, col4w, col5, col5w, tableRight, headerFillPaint, borderPaint, headerTextPaint);
            }

            String chassis = unmatchRemaining.get(i);
            SheetDetails details = stockSheetMap.get(chassis);

            String pDate = (details != null && details.purchaseDate != null) ? details.purchaseDate : "";
            String vendor = (details != null && details.vendor != null) ? details.vendor : "";

            canvas.drawRect(col1, currentY, col1 + col1w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col2, currentY, col2 + col2w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col3, currentY, col3 + col3w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col4, currentY, col4 + col4w, currentY + rowHeight, borderPaint);
            canvas.drawRect(col5, currentY, col5 + col5w, currentY + rowHeight, borderPaint);

            canvas.drawText(String.valueOf(i + 1), col1 + 4, currentY + 15, cellTextPaint);
            canvas.drawText(chassis, col2 + 4, currentY + 15, cellTextPaint);
            canvas.drawText(pDate, col3 + 4, currentY + 15, cellTextPaint);
            canvas.drawText(vendor, col4 + 4, currentY + 15, cellTextPaint);
            canvas.drawText("", col5 + 4, currentY + 15, cellTextPaint);

            currentY += rowHeight;
        }

        pdfDocument.finishPage(page);
        saveAndOpenPdf(pdfDocument, "Stock_Unmatch_Remaining_Report.pdf");
    }

    /** Stock Unmatch Remaining Report-এর হেডার রো — sky-blue ফিল + বর্ডার সহ আঁকে, নতুন পেজে আবার লাগে। */
    private int drawUnmatchReportHeaderRow(Canvas canvas, int currentY, int rowHeight,
                                           int col1, int col1w, int col2, int col2w,
                                           int col3, int col3w, int col4, int col4w,
                                           int col5, int col5w, int tableRight,
                                           Paint headerFillPaint, Paint borderPaint, Paint headerTextPaint) {
        canvas.drawRect(col1, currentY, tableRight, currentY + rowHeight, headerFillPaint);
        canvas.drawRect(col1, currentY, col1 + col1w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col2, currentY, col2 + col2w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col3, currentY, col3 + col3w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col4, currentY, col4 + col4w, currentY + rowHeight, borderPaint);
        canvas.drawRect(col5, currentY, col5 + col5w, currentY + rowHeight, borderPaint);
        canvas.drawText("S.NO", col1 + 4, currentY + 15, headerTextPaint);
        canvas.drawText("CHASSIS NO", col2 + 4, currentY + 15, headerTextPaint);
        canvas.drawText("PURCHASE DATE", col3 + 4, currentY + 15, headerTextPaint);
        canvas.drawText("VENDOR", col4 + 4, currentY + 15, headerTextPaint);
        canvas.drawText("REMARKS", col5 + 4, currentY + 15, headerTextPaint);
        return currentY + rowHeight;
    }

    /** Stock Unmatch Remaining Balance Report-এর Excel (CSV) ভার্সন। */
    private void generateStockUnmatchRemainingCsv() {
        List<String> unmatchRemaining = new ArrayList<>();
        for (String chassis : stockSheetMap.keySet()) {
            if (isHeaderOrInvalidString(chassis)) continue;
            if (!scannedChassisSet.contains(chassis)) {
                unmatchRemaining.add(chassis);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("S.NO,CHASSIS NO,PURCHASE DATE,VENDOR,REMARKS\n");
        for (int i = 0; i < unmatchRemaining.size(); i++) {
            String chassis = unmatchRemaining.get(i);
            SheetDetails details = stockSheetMap.get(chassis);
            String pDate = (details != null && details.purchaseDate != null) ? details.purchaseDate : "";
            String vendor = (details != null && details.vendor != null) ? details.vendor : "";
            sb.append(i + 1).append(",").append(csvEscape(chassis)).append(",")
                    .append(csvEscape(pDate)).append(",").append(csvEscape(vendor)).append(",\n");
        }

        saveCsvAndOffer(sb.toString(), "Stock_Unmatch_Remaining_Report.csv");
    }

    private void generateGenericReportPdf(String title, List<ScannedModel> list) {
        PdfDocument pdfDocument = new PdfDocument();
        Paint paint = new Paint();
        Paint titlePaint = new Paint();

        int pageWidth = 595;
        int pageHeight = 842;
        int margin = 30;
        int startY = 60;
        int rowHeight = 22;

        int currentPage = 1;

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
        PdfDocument.Page page = pdfDocument.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        titlePaint.setTextSize(16);
        titlePaint.setFakeBoldText(true);
        canvas.drawText(title.toUpperCase(Locale.getDefault()), margin, startY, titlePaint);

        titlePaint.setTextSize(12);
        titlePaint.setFakeBoldText(false);
        canvas.drawText("Total Items: " + list.size(), margin, startY + 20, titlePaint);

        int currentY = startY + 50;

        paint.setTextSize(10);
        paint.setFakeBoldText(true);

        canvas.drawText("S.NO", margin, currentY, paint);
        canvas.drawText("CHASSIS NO", margin + 40, currentY, paint);
        canvas.drawText("LOCATION", margin + 220, currentY, paint);
        canvas.drawText("TIMESTAMP", margin + 340, currentY, paint);

        canvas.drawLine(margin, currentY + 5, pageWidth - margin, currentY + 5, paint);
        currentY += rowHeight;

        paint.setFakeBoldText(false);

        for (int i = 0; i < list.size(); i++) {
            if (currentY > pageHeight - margin - 20) {
                pdfDocument.finishPage(page);
                currentPage++;
                pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPage).create();
                page = pdfDocument.startPage(pageInfo);
                canvas = page.getCanvas();
                currentY = startY;

                paint.setFakeBoldText(true);
                canvas.drawText("S.NO", margin, currentY, paint);
                canvas.drawText("CHASSIS NO", margin + 40, currentY, paint);
                canvas.drawText("LOCATION", margin + 220, currentY, paint);
                canvas.drawText("TIMESTAMP", margin + 340, currentY, paint);
                canvas.drawLine(margin, currentY + 5, pageWidth - margin, currentY + 5, paint);
                currentY += rowHeight;
                paint.setFakeBoldText(false);
            }

            ScannedModel m = list.get(i);
            canvas.drawText(String.valueOf(i + 1), margin, currentY, paint);
            canvas.drawText(m.chassis, margin + 40, currentY, paint);
            canvas.drawText(m.location, margin + 220, currentY, paint);
            canvas.drawText(m.timestamp, margin + 340, currentY, paint);

            currentY += rowHeight;
        }

        pdfDocument.finishPage(page);
        String fileName = title.replaceAll("\\s+", "_") + ".pdf";
        saveAndOpenPdf(pdfDocument, fileName);
    }

    /** Stock/Sold/Purchase/Unknown Matches রিপোর্টগুলোর Excel (CSV) ভার্সন — generic। */
    private void generateGenericReportCsv(String fileBaseName, List<ScannedModel> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("S.NO,CHASSIS NO,LOCATION,TIMESTAMP\n");
        for (int i = 0; i < list.size(); i++) {
            ScannedModel m = list.get(i);
            sb.append(i + 1).append(",").append(csvEscape(m.chassis)).append(",")
                    .append(csvEscape(m.location)).append(",").append(csvEscape(m.timestamp)).append("\n");
        }
        saveCsvAndOffer(sb.toString(), fileBaseName + ".csv");
    }

    // ================== ফাইল সেভ + Share/Download (বাগ ফিক্স সহ) ==================
    // পুরোনো কোডে FileProvider authority ছিল ".fileprovider" কিন্তু AndroidManifest.xml-এ
    // আসলে authority="${applicationId}.provider" ঘোষণা করা আছে — এই mismatch-এর কারণেই
    // "Couldn't find meta-data for provider with authority com.example.myapplication..." এই
    // error/ক্র্যাশ হচ্ছিল। এখন দুই জায়গায়ই ".provider" ব্যবহার করা হচ্ছে, তাই মিলে যাবে।
    // এছাড়া এখন ফাইল সেভ হওয়ার সাথে সাথে অটো-ওপেন (ACTION_VIEW) হবে না — শুধু সেভ হবে,
    // এবং একটা ডায়ালগে "Share" অপশন দেখানো হবে যাতে চাইলে শেয়ার করা যায়, না চাইলে শুধু
    // ফোনে সেভ (download) হয়ে থাকবে।
    private void saveAndOpenPdf(PdfDocument pdfDocument, String fileName) {
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName);
        try {
            pdfDocument.writeTo(new FileOutputStream(file));
            pdfDocument.close();
            offerShareOrClose(file, "application/pdf", fileName);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "PDF Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveCsvAndOffer(String csvContent, String fileName) {
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName);
        try {
            FileWriter writer = new FileWriter(file);
            writer.write(csvContent);
            writer.flush();
            writer.close();
            offerShareOrClose(file, "text/csv", fileName);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Excel Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** ফাইল সেভ হওয়ার পর দেখায়: কোথায় সেভ হলো, এবং চাইলে Share করার অপশন। */
    private void offerShareOrClose(File file, String mimeType, String fileName) {
        Toast.makeText(this, "Saved: " + fileName, Toast.LENGTH_SHORT).show();
        new AlertDialog.Builder(this)
                .setTitle("Saved: " + fileName)
                .setMessage("ফাইলটা ফোনে ডাউনলোড/সেভ হয়ে গেছে:\n" + file.getAbsolutePath()
                        + "\n\nচাইলে এখনই Share করতে পারেন, অথবা শুধু OK চাপুন (ফাইল সেভ হয়েই থাকবে)।")
                .setPositiveButton("Share", (d, w) -> shareFile(file, mimeType))
                .setNegativeButton("OK", null)
                .show();
    }

    private void shareFile(File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Report Via"));
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Share Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
