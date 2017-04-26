/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.actions;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.part.FileEditorInput;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.editors.ScaveEditor;
import org.omnetpp.scave.model.InputFile;
import org.omnetpp.scave.model2.ScaveModelUtil;

/**
 * Edits an InputFile item.
 */
public class EditInputFileAction extends AbstractScaveAction {
    public EditInputFileAction() {
        setText("Edit Input...");
        setImageDescriptor(ScavePlugin.getImageDescriptor("icons/full/etool16/edit.png"));
    }

    @Override
    protected void doRun(ScaveEditor editor, IStructuredSelection selection) {
        InputFile inputFile = (InputFile)selection.getFirstElement();
        IContainer baseDir = ((FileEditorInput)editor.getEditorInput()).getFile().getParent();
        InputFileDialog dialog = new InputFileDialog(editor.getSite().getShell(), "Edit Input", inputFile.getName(), false, baseDir);
        if (dialog.open() == Window.OK) {
            String value = dialog.getValue();
            ScaveModelUtil.setInputFile(editor.getEditingDomain(), inputFile, value);
        }
    }

    @Override
    protected boolean isApplicable(ScaveEditor editor, IStructuredSelection selection) {
        return selection.getFirstElement() instanceof InputFile;
    }
}