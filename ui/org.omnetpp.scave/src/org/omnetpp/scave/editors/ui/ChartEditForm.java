/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.omnetpp.common.Debug;
import org.omnetpp.common.contentassist.ContentAssistUtil;
import org.omnetpp.common.ui.SWTFactory;
import org.omnetpp.common.ui.StyledTextUndoRedoManager;
import org.omnetpp.common.util.Converter;
import org.omnetpp.common.util.UIUtils;
import org.omnetpp.common.wizard.XSWTDataBinding;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.assist.FilterContentProposalProvider;
import org.omnetpp.scave.assist.MatplotlibrcContentProposalProvider;
import org.omnetpp.scave.assist.NativePlotPropertiesContentProposalProvider;
import org.omnetpp.scave.charttemplates.ChartTemplateRegistry;
import org.omnetpp.scave.engine.ResultFileManager;
import org.omnetpp.scave.engine.Run;
import org.omnetpp.scave.model.Chart;
import org.omnetpp.scave.model.Chart.DialogPage;
import org.omnetpp.scave.model.ChartTemplate;
import org.omnetpp.scave.model.Property;
import org.xml.sax.SAXException;

import com.swtworkbench.community.xswt.XSWT;

public class ChartEditForm {

    public static final String PROP_ACTIVE_TAB = "active-tab";

    protected Chart chart;
    protected ChartTemplateRegistry chartTemplateRegistry;
    protected ResultFileManager manager;
    protected Map<String,Control> xswtWidgetMap = new HashMap<>();

    protected static final String USER_DATA_KEY = "ChartEditForm";

    public ChartEditForm(Chart chart, ChartTemplateRegistry chartTemplateRegistry, ResultFileManager manager) {
        this.chart = chart;
        this.chartTemplateRegistry = chartTemplateRegistry;
        this.manager = manager;
    }

    /**
     * Creates the controls of the dialog.
     */
    public void populatePanel(Composite panel) {
        panel.setLayout(new GridLayout(1, false));
        final TabFolder tabfolder = createTabFolder(panel);

        for (DialogPage page : chart.getDialogPages())
            createTab(tabfolder, page.label, page.xswtForm);

        fillComboBoxes();
        populateControls();
        addExtraWidgetLogic();
        validatePropertyNames();

        // switch to the last used page
        String defaultPage = getDialogSettings().get(PROP_ACTIVE_TAB);
        if (defaultPage != null)
            for (TabItem tabItem : tabfolder.getItems())
                if (tabItem.getText().equals(defaultPage)) {
                    tabfolder.setSelection(tabItem);
                    break;
                }

        // save current tab as dialog setting (the code is here because there's no convenient function that is invoked on dialog close (???))
        tabfolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                TabItem[] selectedTabs = tabfolder.getSelection();
                if (selectedTabs.length > 0)
                    getDialogSettings().put(PROP_ACTIVE_TAB, selectedTabs[0].getText());
            }
        });
    }

    private void validatePropertyNames() {
        ChartTemplate template = chartTemplateRegistry.findTemplateByID(chart.getTemplateID());

        if (template == null)
            return;

        Set<String> templatePropertyNames = new HashSet<String>(template.getPropertyNames());

        Set<String> formPropertyNames = xswtWidgetMap.keySet();
        if (!formPropertyNames.equals(templatePropertyNames)) {
            Set<String> declaredNotOnForm = new HashSet<String>(templatePropertyNames);
            declaredNotOnForm.removeAll(formPropertyNames);

            Set<String> onFormNotDeclared = new HashSet<String>(formPropertyNames);
            onFormNotDeclared.removeAll(templatePropertyNames);

            if (!declaredNotOnForm.isEmpty())
                Debug.println("Uneditable chart properties of template '" + template.getName() + "' : " + declaredNotOnForm);
            if (!onFormNotDeclared.isEmpty())
                throw new RuntimeException("Edited properties not declared: " + onFormNotDeclared);
        }
    }

    protected IDialogSettings getDialogSettings() {
        final String KEY = "ChartEditForm";
        IDialogSettings dialogSettings = ScavePlugin.getDefault().getDialogSettings();
        IDialogSettings section = dialogSettings.getSection(KEY);
        if (section == null)
            section = dialogSettings.addNewSection(KEY);
        return section;
    }

    List<String> getComboContents(String contentString) {
        List<String> result = new ArrayList<String>();

        ResultFileManager.runWithReadLock(manager, () -> {

            for (String part : contentString.split(",")) {
                if (part.startsWith("$")) {
                    switch (part) {
                    case "$scalarnames":
                        for (String name : manager.getUniqueResultNames(manager.getAllScalars(false, false)).keys().toArray())
                            result.add(name);
                        break;
                    case "$vectornames":
                        for (String name : manager.getUniqueResultNames(manager.getAllVectors()).keys().toArray())
                            result.add(name);
                        break;
                    case "$histogramnames":
                        for (String name : manager.getUniqueResultNames(manager.getAllHistograms()).keys().toArray())
                            result.add(name);
                        break;
                    case "$statisticnames":
                        for (String name : manager.getUniqueResultNames(manager.getAllStatistics()).keys().toArray())
                            result.add(name);
                        break;
                    case "$itervarnames":
                        Set<String> itervars = new HashSet<String>();

                        for (Run run : manager.getRuns().toArray())
                            for (String itervar : run.getIterationVariables().keys().toArray())
                                itervars.add(itervar);

                        result.addAll(itervars);
                        break;
                    case "$runattrnames":
                        Set<String> runattrs = new HashSet<String>();

                        for (Run run : manager.getRuns().toArray())
                            for (String runattr : run.getAttributes().keys().toArray())
                                runattrs.add(runattr);

                        result.addAll(runattrs);
                        break;
                    default:
                        result.add("Unknown: " + part);
                        break;
                    }
                }
                else {
                    result.add(part);
                }
            }
        });

        return result;
    }

    protected void populateControls() {
        for (String propId : xswtWidgetMap.keySet()) {
            Property prop = chart.lookupProperty(propId);
            if (prop != null) {
                String value = prop.getValue();
                if (value != null) {
                    Control control = xswtWidgetMap.get(propId);
                    try {
                        XSWTDataBinding.putValueIntoControl(control, value, null);
                    } catch (Exception e) {
                        MessageDialog.openError(null, "Error", String.format("Error populating dialog field '%s' (%s) with value '%s': ", propId, control.getClass().getSimpleName(), value) + e.getMessage());
                    }
                }
            }
        }
    }

    protected void addExtraWidgetLogic() {
        // setting up content assist and "enabler" checkboxes
        for (String key : xswtWidgetMap.keySet()) {
            Control control = xswtWidgetMap.get(key);

            if (control instanceof StyledText) {
               StyledTextUndoRedoManager.getOrCreateManagerOf((StyledText)control);
            }

            String contentAssist = (String)control.getData("contentAssist");
            if (contentAssist != null)  {
                IContentProposalProvider proposalProvider = null;
                if (contentAssist.equalsIgnoreCase("filter"))
                    proposalProvider = new FilterContentProposalProvider();
                else if (contentAssist.equalsIgnoreCase("plotproperties"))
                    proposalProvider = new NativePlotPropertiesContentProposalProvider();
                else if (contentAssist.equalsIgnoreCase("matplotlibrc"))
                    proposalProvider = new MatplotlibrcContentProposalProvider();
                else
                    ScavePlugin.getDefault().getLog().warn("Invalid value for 'contentAssist' attribute in XSWT file: '" + contentAssist + "'");

                if (proposalProvider != null) {
                    if (control instanceof Text)
                        ContentAssistUtil.configureText((Text)control, proposalProvider);
                    else if (control instanceof StyledText)
                        ContentAssistUtil.configureStyledText((StyledText)control, proposalProvider);
                    else
                        ScavePlugin.getDefault().getLog().warn("'contentAssist' attribute in XSWT file is ignored for widget of type '" + control.getClass().getSimpleName() + "', it is only valid for Text and StyledText widgets");
                }
            }

            String isEnabler = (String)control.getData("isEnabler");
            if (control instanceof Button && isEnabler != null && isEnabler.equalsIgnoreCase("true")) {
                Button button = (Button)control;

                button.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        for (Control sibling: control.getParent().getChildren())
                            if (sibling != control)
                                sibling.setEnabled(button.getSelection());
                    }
                });

                // apply initial state
                for (Control sibling: control.getParent().getChildren())
                    if (sibling != control)
                        sibling.setEnabled(button.getSelection());
            }
        }
    }

    protected void fillComboBoxes() {
        for (String key : xswtWidgetMap.keySet()) {
            Control control = xswtWidgetMap.get(key);
            String content = (String)control.getData("content");
            if (control instanceof Combo && content != null) {
                Combo combo = (Combo)control;
                for (String comboItem : getComboContents(content))
                    combo.add(comboItem);
            }
        }
    }

    private TabFolder createTabFolder(Composite parent) {
        TabFolder tabfolder = new TabFolder(parent, SWT.NONE);
        tabfolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return tabfolder;

    }

    protected Composite createTab(TabFolder tabfolder, String label, String xswtForm) {
        TabItem tabitem = new TabItem(tabfolder, SWT.NONE);
        tabitem.setText(label);

        try {
            Composite xswtHolder = SWTFactory.createComposite(tabfolder, 1, 1, SWTFactory.GRAB_AND_FILL_HORIZONTAL);
            tabitem.setControl(xswtHolder);
            validateXml(xswtForm); // because XSWT is not very good at it
            Map<String,Control> tempWidgetMap = XSWT.create(xswtHolder, new ByteArrayInputStream(xswtForm.getBytes()));
            xswtWidgetMap.putAll(tempWidgetMap);
            return xswtHolder;
        }
        catch (Exception e) {
            // log
            IStatus status = new Status(IStatus.ERROR, ScavePlugin.PLUGIN_ID, "Cannot create dialog page '" + label + "' for chart template '" + chart.getTemplateID() + "'", e);
            ScavePlugin.log(status);

            // show error page
            tabitem.setImage(UIUtils.ICON_ERROR);
            tabitem.getControl().dispose();
            Composite composite = SWTFactory.createComposite(tabfolder, 1, 1, SWTFactory.GRAB_AND_FILL_HORIZONTAL);
            tabitem.setControl(composite);
            Label heading = SWTFactory.createWrapLabel(composite, "An error occurred while setting up page from XSWT source", 1);
            heading.setFont(JFaceResources.getHeaderFont());
            SWTFactory.createWrapLabel(composite, e.getMessage(), 1);
            return null;
        }
    }

    protected void validateXml(String xswtForm) throws SAXException, IOException, ParserConfigurationException {
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        db.parse(new ByteArrayInputStream(xswtForm.getBytes()));
    }

    public Map<String, String> collectProperties() {
        Map<String, String> result = new HashMap<>();
        for (String k : xswtWidgetMap.keySet()) {
            Control control = xswtWidgetMap.get(k);
            Object value = XSWTDataBinding.getValueFromControl(control, null);

            Property chartProperty = chart.lookupProperty(k);
            String chartValueString = chartProperty == null ? null : chartProperty.getValue();
            String formValueString = Converter.objectToString(value);

            if (!formValueString.equals(chartValueString))
                result.put(k, formValueString);
        }
        return result;
    }

    /**
     * Returns the selected radio button as the enum value it represents.
     */
    @SuppressWarnings("unchecked")
    protected static <T extends Enum<T>> T getSelection(Button[] radios, Class<T> type) {
        for (int i = 0; i < radios.length; ++i)
            if (radios[i].getSelection())
                return (T)radios[i].getData(USER_DATA_KEY);
        return null;
    }

    protected static <T extends Enum<T>> T resolveEnum(String text, Class<T> type) {
        T[] values = type.getEnumConstants();
        for (int i = 0; i < values.length; ++i)
            if (values[i].toString().equals(text))
                return values[i];
        return null;
    }

    /**
     * Select the radio button representing the enum value.
     */
    protected static void setSelection(Button[] radios, Enum<?> value) {
        for (int i = 0; i < radios.length; ++i)
            radios[i].setSelection(radios[i].getData(USER_DATA_KEY) == value);
    }

    /**
     * Sets the enabled state of the controls under {@code composite}
     * except the given {@code control} to {@code enabled}.
     */
    protected void setEnabledDescendants(Composite composite, boolean enabled, Control except) {
        for (Control child : composite.getChildren()) {
            if (child != except)
                child.setEnabled(enabled);
            if (child instanceof Composite)
                setEnabledDescendants((Composite)child, enabled, except);
        }
    }
}
