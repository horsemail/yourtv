package com.horsenma.yourtv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.horsenma.yourtv.databinding.DialogConfirmationBinding

class ConfirmationFragment(
    private val listener: ConfirmationListener,
    private val message: String,
    private val update: Boolean
) : DialogFragment() {

    private var _binding: DialogConfirmationBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.DialogTheme)
        isCancelable = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogConfirmationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.message.text = message

        if (update) {
            binding.confirmButton.visibility = View.VISIBLE
            binding.cancelButton.visibility = View.VISIBLE
            binding.okButton.visibility = View.GONE

            binding.confirmButton.setOnClickListener {
                listener.onConfirm()
                dismiss()
            }
            binding.cancelButton.setOnClickListener {
                listener.onCancel()
                dismiss()
            }

            binding.confirmButton.requestFocus()
        } else {
            binding.confirmButton.visibility = View.GONE
            binding.cancelButton.visibility = View.GONE
            binding.okButton.visibility = View.VISIBLE

            binding.okButton.setOnClickListener {
                dismiss()
            }

            binding.okButton.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    interface ConfirmationListener {
        fun onConfirm()
        fun onCancel()
    }
}