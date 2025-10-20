package com.champion.king.ui.settings

import android.R
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.FragmentManager
import com.champion.king.ScratchBoardPreviewFragment
import com.champion.king.constants.ScratchCardConstants
import com.champion.king.databinding.FragmentSettingsBinding
import com.champion.king.model.NumberConfiguration
import com.champion.king.model.ScratchCard

class SettingsUIManager(
    private val binding: FragmentSettingsBinding,
    private val context: Context,
    private val childFragmentManager: FragmentManager
) {
    var currentScratchBoardPreviewFragment: ScratchBoardPreviewFragment? = null
        private set

    private var isSpinnerProgrammaticChange = false

    fun setupSpinners() {
        setupScratchTypesSpinner()
        setupNumberSpinners()
    }

    private fun setupScratchTypesSpinner() {
        binding.spinnerScratchesCount.adapter = ArrayAdapter(
            context,
            R.layout.simple_spinner_item,
            ScratchCardConstants.SCRATCH_TYPES_LIST
        ).apply {
            setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerScratchesCount.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selectedType = ScratchCardConstants.SCRATCH_TYPES_LIST[pos]
                if (!isSpinnerProgrammaticChange) {
                    loadScratchBoardPreview(selectedType, null)
                } else {
                    isSpinnerProgrammaticChange = false
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupNumberSpinners() {
        val numberAdapter = ArrayAdapter(
            context,
            R.layout.simple_spinner_item,
            ScratchCardConstants.NUMBER_OPTIONS
        ).apply {
            setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerClawsCount.adapter = numberAdapter
        binding.spinnerGiveawayCount.adapter = numberAdapter
    }

    fun displayScratchCardDetails(card: ScratchCard?) {
        if (card != null) {
            populateUIWithCardData(card)
            loadScratchBoardPreview(
                ScratchCardConstants.SCRATCH_COUNT_MAP[card.scratchesType] ?: ScratchCardConstants.DEFAULT_SCRATCH_TYPE,
                card.numberConfigurations
            )
        } else {
            clearUI()
            loadScratchBoardPreview(ScratchCardConstants.DEFAULT_SCRATCH_TYPE, null)
        }
    }

    private fun populateUIWithCardData(card: ScratchCard) {
        val typeString = ScratchCardConstants.SCRATCH_COUNT_MAP[card.scratchesType] ?: ScratchCardConstants.DEFAULT_SCRATCH_TYPE

        isSpinnerProgrammaticChange = true
        binding.spinnerScratchesCount.setSelection(
            ScratchCardConstants.SCRATCH_TYPES_LIST.indexOf(typeString).coerceAtLeast(0)
        )

        binding.editTextSpecialPrize.setText(card.specialPrize ?: "")
        binding.editTextGrandPrize.setText(card.grandPrize ?: "")

        binding.spinnerClawsCount.setSelection(
            ScratchCardConstants.NUMBER_OPTIONS.indexOf(card.clawsCount?.toString() ?: "1").coerceAtLeast(0)
        )
        binding.spinnerGiveawayCount.setSelection(
            ScratchCardConstants.NUMBER_OPTIONS.indexOf(card.giveawayCount?.toString() ?: "1").coerceAtLeast(0)
        )
    }

    private fun clearUI() {
        isSpinnerProgrammaticChange = true
        binding.spinnerScratchesCount.setSelection(
            ScratchCardConstants.SCRATCH_TYPES_LIST.indexOf(ScratchCardConstants.DEFAULT_SCRATCH_TYPE)
        )
        binding.editTextSpecialPrize.setText("")
        binding.editTextGrandPrize.setText("")
        binding.spinnerClawsCount.setSelection(0)
        binding.spinnerGiveawayCount.setSelection(0)
    }

    fun loadScratchBoardPreview(
        scratchesType: String,
        numberConfigurations: List<NumberConfiguration>?
    ) {
        val fragment = if (!numberConfigurations.isNullOrEmpty()) {
            ScratchBoardPreviewFragment.newInstance(scratchesType, numberConfigurations)
        } else {
            ScratchBoardPreviewFragment.newInstance(scratchesType)
        }

        currentScratchBoardPreviewFragment = fragment
        childFragmentManager.beginTransaction()
            .replace(binding.scratchBoardArea.id, fragment)
            .commitAllowingStateLoss()
    }

    fun updateInUseButtonUI(card: ScratchCard?) {
        if (card == null) {
            binding.buttonToggleInuse.isEnabled = false
            binding.buttonToggleInuse.text = "無資料可設定"
            return
        }

        binding.buttonToggleInuse.isEnabled = true
        binding.buttonToggleInuse.text = if (card.inUsed) "改為未使用" else "設為使用中（★）"
    }

    fun updateActionButtonsUI(card: ScratchCard?) {
        val hasData = card?.serialNumber != null
        val notInUse = card?.inUsed != true
        binding.buttonReturnSelected.isEnabled = hasData && notInUse
        binding.buttonDeleteSelected.isEnabled = hasData && notInUse
    }
}