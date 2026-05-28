/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Context // 💡 SharedPreferences အတွက် ထည့်သွင်းထားသည်
import android.content.res.Resources // 💡 Build Error ရှင်းရန် ပြန်လည်ထည့်သွင်းထားသည်
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.EditText // 💡 Password Input အတွက် ထည့်သွင်းထားသည်
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog // 💡 Dialog ပြသရန် ထည့်သွင်းထားသည်
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.updater.SnackbarUpdateShower
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.net.URL

class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch { TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) } }
        }
    }

    private val snackbarUpdateShower = SnackbarUpdateShower(this)

    // 🔑 SharedPreferences ကို သုံးပြီး လုံခြုံရေးအခြေအနေကို စစ်ဆေးရန် Helper Function
    private fun getSharedPrefs() = requireContext().getSharedPreferences("PhoenixVPNPrefs", Context.MODE_PRIVATE)

    // 🔘 Generate ခလုတ်နှိပ်လျှင် အလုပ်လုပ်မည့် မူရင်း Function ကို စစ်ဆေးမှုခံရန် ပြင်ဆင်ခြင်း
    fun onGenerateClicked() {
        val sharedPrefs = getSharedPrefs()
        
        // 🔒 GitHub တွင် ဤနေရာ၌ Password ကို "157269" ပြောင်းလဲသတ်မှတ်နိုင်သည်
        val savedPassword = sharedPrefs.getString("generate_password", "157269") ?: "157269"
        val isAlreadyUnlocked = sharedPrefs.getBoolean("is_generate_unlocked", false)

        if (isAlreadyUnlocked) {
            // App မပိတ်သေးဘဲ ယခင်က တစ်ကြိမ် မှန်ထားဖူးလျှင် တိုက်ရိုက် Config ဆွဲမည်
            proceedWithGeneration()
        } else {
            // ပထမဆုံးအကြိမ်ဆိုလျှင် Password တောင်းခံမည့် Dialog Box ပြသမည်
            showPasswordDialog(savedPassword)
        }
    }

    // 🖥️ Password တောင်းခံသည့် Dialog UI ဆောက်လုပ်ခြင်း
    private fun showPasswordDialog(savedPassword: String) {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password ရိုက်ထည့်ပါ"
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(context)
            .setTitle("လုံခြုံရေး စစ်ဆေးခြင်း")
            .setMessage("Generate ပြုလုပ်ရန်အတွက် Password လိုအပ်ပါသည်")
            .setView(input)
            .setCancelable(false) // ဘေးနှိပ်လျှင် ပိတ်မသွားစေရန် တားမြစ်ခြင်း
            .setPositiveButton("Confirm") { dialog, _ ->
                val enteredPassword = input.text.toString()
                
                if (enteredPassword == savedPassword) {
                    // 🔑 Password မှန်လျှင် နောက်တစ်ကြိမ် ထပ်မတောင်းရန် True လုပ်ပေးလိုက်သည်
                    getSharedPrefs().edit().putBoolean("is_generate_unlocked", true).apply()
                    dialog.dismiss()
                    
                    // Core Logic ကို ဆက်လုပ်ခိုင်းခြင်း
                    proceedWithGeneration()
                } else {
                    // ❌ မှားလျှင် Alert Toast ပြပြီး Dialog ကို ပြန်ဖွင့်ခိုင်းမည်
                    Toast.makeText(context, "Password မှားယွင်းနေပါသည်။", Toast.LENGTH_SHORT).show()
                    showPasswordDialog(savedPassword)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // 🚀 သင့်ရဲ့ မူရင်း Core Logic အပြည့်အစုံ (တစ်လုံးမှ မပြောင်းလဲဘဲ သီးသန့်ထုတ်ထားခြင်း)
    private fun proceedWithGeneration() {
        lifecycleScope.launch {
            try {
                showSnackbar("Generating config...")
                val configText = withContext(Dispatchers.IO) {
                    // သတ်မှတ်ထားသော ptugyi link မှ plain text config ကို တိုက်ရိုက်ဖတ်ပါမည်
                    URL("https://ptugyi.netlify.app/.netlify/functions/generate").readText().trim()
                }

                // Config စာသားမှန်ကန်မှု ရှိမရှိ စစ်ဆေးခြင်း
                if (!configText.contains("[Interface]") && !configText.contains("Interface")) {
                    throw Exception("Invalid configuration data received from server")
                }

                // Core Logic: ကျလာသော Config စာသားထဲမှ Endpoint IP (ဥပမာ- 162.159.192.1) ကို ရှာဖွေပြီး ဖတ်ယူပါမည်
                val matchResult = Regex("""Endpoint\s*=\s*([\d.]+):""").find(configText)
                var filename = matchResult?.groups?.get(1)?.value ?: "162.159.192.13"
                filename = filename.trim()

                val tunnelConfig = com.wireguard.config.Config.parse(configText.reader().buffered())
                
                // တန်ဖိုးများကို မပြောင်းလဲဘဲ Generator ထဲက ထွက်လာသည့် IP နာမည်အတိုင်း တိုက်ရိုက် ဆောက်ပေးပါမည်
                Application.getTunnelManager().create(filename, tunnelConfig)
                showSnackbar("Config generated: $filename")
            } catch (e: Exception) {
                Log.e(TAG, "Generate failed", e)
                showSnackbar("Generate failed: ${e.message}")
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        binding?.apply {
            createFab.setOnClickListener {
                onGenerateClicked()
            }
            executePendingBindings()
            snackbarUpdateShower.attach(mainContainer, createFab)
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels)?.setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch { binding!!.tunnels = Application.getTunnelManager().getTunnels() }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(position)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createFab)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }

                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = Application.getTunnelManager().getTunnels()
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) {
                resources = activity!!.resources
            }
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) return
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    if (!show) view.visibility = View.GONE
                }
                override fun onAnimationStart(animation: Animation?) {
                    if (show) view.visibility = View.VISIBLE
                }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
