package app.honorpad8.debloater

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/*
 * Android 12+ QR-code setup ("tap the welcome screen 6 times") asks the app two questions
 * before it can become device owner. These answer them without showing any UI.
 */

/** Which kind of setup: always "fully managed device" (device owner). */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        setResult(RESULT_OK, result)
        finish()
    }
}

/** Called once setup has made the app device owner: start enforcing straight away. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store(this).log("set up as device owner by QR provisioning")
        Scheduler.runSoon(this, "provisioned")
        setResult(RESULT_OK)
        finish()
    }
}
