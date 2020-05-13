/*--------------------------------------------------------------*
  Copyright (C) 2006-2020 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.model.commands;

import java.util.Arrays;
import java.util.Collection;

import org.omnetpp.scave.model.AnalysisItem;
import org.omnetpp.scave.model.ModelObject;

public class SetChartNameCommand implements ICommand {

    private AnalysisItem item;
    private String oldName;
    private String newName;

    public SetChartNameCommand(AnalysisItem item, String newValue) {
        this.item = item;
        this.newName = newValue;
    }

    @Override
    public void execute() {
        oldName = item.getName();
        item.setName(newName);
    }

    @Override
    public void undo() {
        item.setName(oldName);
    }

    @Override
    public void redo() {
        execute();
    }

    @Override
    public String getLabel() {
        return "Set chart name";
    }

    @Override
    public Collection<ModelObject> getAffectedObjects() {
        return Arrays.asList(item);
    }

}
