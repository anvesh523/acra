package org.acra.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.widget.Toast;
import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.config.ACRAConfig;
import org.acra.file.BulkReportDeleter;
import org.acra.file.CrashReportFileNameParser;
import org.acra.prefs.PrefUtils;
import org.acra.prefs.SharedPreferencesFactory;
import org.acra.sender.SenderServiceStarter;
import org.acra.util.PackageManagerWrapper;
import org.acra.util.ToastSender;

/**
 * Looks for any existing reports and starts sending them.
 */
public final class AvailableReportChecker {

    private final Context context;
    private final ACRAConfig config;
    private final boolean sendReports;

    public AvailableReportChecker(Context context, ACRAConfig config, boolean sendReports) {
        this.context = context;
        this.config = config;
        this.sendReports = sendReports;
    }

    /**
     * This method looks for pending reports and does the action required depending on the interaction mode set.
     */
    public void execute() {

        if (config.deleteOldUnsentReportsOnApplicationStart()) {
            deleteUnsentReportsFromOldAppVersion();
        }

        final ReportingInteractionMode reportingInteractionMode = config.mode();

        if ((reportingInteractionMode == ReportingInteractionMode.NOTIFICATION || reportingInteractionMode == ReportingInteractionMode.DIALOG)
                && config.deleteUnapprovedReportsOnApplicationStart()) {
            // NOTIFICATION or DIALOG mode, and there are unapproved reports to
            // send (latest notification/dialog has been ignored: neither accepted nor refused).
            // The application developer has decided that these reports should not be renotified ==> destroy all reports bar one.
            new BulkReportDeleter(context).deleteReports(false, 1);
        }

        final CrashReportFinder reportFinder = new CrashReportFinder(context);
        final String[] filesList = reportFinder.getCrashReportFiles();
        if (filesList != null && filesList.length > 0) {

            // Immediately send reports for SILENT and TOAST modes.
            // Immediately send reports in NOTIFICATION mode only if they are all silent or approved.
            // If there is still one unapproved report in NOTIFICATION mode, notify it.
            // If there are unapproved reports in DIALOG mode, show the dialog

            final boolean onlySilentOrApprovedReports = containsOnlySilentOrApprovedReports(filesList);

            if (reportingInteractionMode == ReportingInteractionMode.SILENT
                    || reportingInteractionMode == ReportingInteractionMode.TOAST
                    || (onlySilentOrApprovedReports && (reportingInteractionMode == ReportingInteractionMode.NOTIFICATION || reportingInteractionMode == ReportingInteractionMode.DIALOG))) {

                if (reportingInteractionMode == ReportingInteractionMode.TOAST && !onlySilentOrApprovedReports) {
                    // Display the Toast in TOAST mode only if there are non-silent reports.
                    ToastSender.sendToast(context, config.resToastText(), Toast.LENGTH_LONG);
                }

                if (sendReports) {
                    final SenderServiceStarter starter = new SenderServiceStarter(context, config);
                    starter.startService(false, false);
                }
            }
        }
    }

    /**
     * Delete any old unsent reports if this is a newer version of the app than when we last started.
     */
    private void deleteUnsentReportsFromOldAppVersion() {
        final SharedPreferences prefs = new SharedPreferencesFactory(context, config).create();
        final long lastVersionNr = prefs.getInt(ACRA.PREF_LAST_VERSION_NR, 0);
        final int appVersion = getAppVersion();

        if (appVersion > lastVersionNr) {
            final BulkReportDeleter reportDeleter = new BulkReportDeleter(context);
            reportDeleter.deleteReports(true, 0);
            reportDeleter.deleteReports(false, 0);

            final SharedPreferences.Editor prefsEditor = prefs.edit();
            prefsEditor.putInt(ACRA.PREF_LAST_VERSION_NR, appVersion);
            PrefUtils.save(prefsEditor);
        }
    }

    /**
     * @return app version or 0 if PackageInfo was not available.
     */
    private int getAppVersion() {
        final PackageManagerWrapper packageManagerWrapper = new PackageManagerWrapper(context);
        final PackageInfo packageInfo = packageManagerWrapper.getPackageInfo();
        return (packageInfo == null) ? 0 : packageInfo.versionCode;
    }

    /**
     * Checks if an array of reports files names contains only silent or
     * approved reports.
     *
     * @param reportFileNames
     *            Array of report locations to check.
     * @return True if there are only silent or approved reports. False if there
     *         is at least one non-approved report.
     */
    private boolean containsOnlySilentOrApprovedReports(String[] reportFileNames) {
        final CrashReportFileNameParser fileNameParser = new CrashReportFileNameParser();
        for (String reportFileName : reportFileNames) {
            if (!fileNameParser.isApproved(reportFileName)) {
                return false;
            }
        }
        return true;
    }
}
