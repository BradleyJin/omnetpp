/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.omnetpp.common.image.ImageFactory;
import org.omnetpp.common.swt.custom.StyledText;
import org.omnetpp.common.util.HTMLUtils;
import org.omnetpp.common.util.StringUtils;
import org.omnetpp.common.util.UIUtils;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.charttemplates.ChartTemplateRegistry;
import org.omnetpp.scave.editors.ScaveEditor;
import org.omnetpp.scave.model.ChartTemplate;
import org.omnetpp.scave.model2.ScaveModelUtil;
import org.osgi.framework.Bundle;


public class ChartTemplateGalleryDialog extends TitleAreaDialog {
    private String title;
    private ScaveEditor editor;
    private SashForm sashForm;
    private TableViewer tableViewer;
    private Composite styledTextHolder;
    private StyledText styledText;
    private Label voidLabel;
    private Map<ChartTemplate,StyledText> styledTexts = new HashMap<>();
    private ChartTemplate selectedTemplate;

    private ImageRegistry imageRegistry = new ImageRegistry();

    private int selectionItemTypes = 0;

    /**
     * LabelProvider for chart templates.
     */
    public class ChartTemplateLabelProvider extends LabelProvider {
        @Override
        public Image getImage(Object element) {
            ChartTemplate template = (ChartTemplate)element;
            String iconPath = template.getIconPath();
            if (iconPath != null)
                return ScavePlugin.getCachedImage(iconPath);
            return null;
        }

        @Override
        public String getText(Object element) {
            ChartTemplate template = (ChartTemplate)element;
            return template.getName();
        }

    };

    public ChartTemplateGalleryDialog(Shell parentShell, ScaveEditor editor) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.MAX | SWT.RESIZE);
        this.title = "Select Chart Template";
        this.editor = editor;
    }

    public ChartTemplateGalleryDialog(Shell parentShell, ScaveEditor editor, int selectionItemTypes) {
        this(parentShell, editor);
        this.selectionItemTypes = selectionItemTypes;
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings() {
        return UIUtils.getDialogSettings(ScavePlugin.getDefault(), getClass().getName());
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        if (title != null)
            shell.setText(title);
    }

    protected Label createLabel(Composite composite, String text) {
        Label label = new Label(composite, SWT.NONE);
        label.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        label.setText(text);
        return label;
    }

    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Select Chart Template");
        setMessage("Select template for the new chart");

        // page group
        Composite dialogArea = (Composite) super.createDialogArea(parent);

        Composite composite = new Composite(dialogArea, SWT.NONE);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        composite.setLayout(new GridLayout());

        createLabel(composite, selectionItemTypes == 0 ? "Available templates:" : "Matching templates:");

        sashForm = new SashForm(composite, SWT.NONE);
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sashForm.setLayout(new GridLayout(2,false));

        // content area
        tableViewer = new TableViewer(sashForm, SWT.BORDER);
        GridData data = new GridData(GridData.FILL_BOTH);
        data.widthHint = 200;
        tableViewer.getTable().setLayoutData(data);

        tableViewer.setContentProvider(new ArrayContentProvider());
        tableViewer.setLabelProvider(new ChartTemplateLabelProvider());

        ChartTemplateRegistry registry = editor.getChartTemplateRegistry();
        List<ChartTemplate> templates = selectionItemTypes == 0 ? registry.getAllTemplates() : registry.getChartTemplatesForResultTypes(selectionItemTypes);
        tableViewer.setInput(templates);

        styledTextHolder = new Composite(sashForm, SWT.NONE);
        styledTextHolder.setLayoutData(new GridData(GridData.FILL_BOTH));
        styledTextHolder.setLayout(new StackLayout());
        voidLabel = new Label(styledTextHolder, SWT.NONE);

        tableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                ChartTemplate template = getTableSelection();
                switchToTemplate(template);
            }
        });

        Dialog.applyDialogFont(sashForm);

        tableViewer.refresh();

        String w0 = getDialogBoundsSettings().get("sash.weight0");
        String w1 = getDialogBoundsSettings().get("sash.weight1");
        if (StringUtils.isNumeric(w0) && StringUtils.isNumeric(w1))
            sashForm.setWeights(new int[] {Integer.parseInt(w0), Integer.parseInt(w1)});

        return composite;
    }

    protected void switchToTemplate(ChartTemplate template) {
        if (template != selectedTemplate) {
            if (template != null) {
                styledText = styledTexts.get(template);
                if (styledText == null) {
                    styledText = new StyledText(styledTextHolder, SWT.BORDER|SWT.WRAP|SWT.V_SCROLL|SWT.H_SCROLL|SWT.READ_ONLY);
                    String html = getDescriptionAsHtml(template);
                    HTMLUtils.htmlToStyledText(html, styledText, (String name) -> getCachedImage(template, name));
                    styledTexts.put(template, styledText);
                }
                ((StackLayout)styledTextHolder.getLayout()).topControl = styledText;
                styledTextHolder.layout();
            }
            else {
                styledText = null;
                ((StackLayout)styledTextHolder.getLayout()).topControl = voidLabel;
                styledTextHolder.layout();
            }
            selectedTemplate = template;
        }
    }

    protected String getDescriptionAsHtml(ChartTemplate template) {
        String html = "";

        html += "<p><font size='-1'>";
        html += "Template ID: " + template.getId() + " | ";
        html += "type: " + template.getChartType() + " | ";
        String supportedResultTypes = ScaveModelUtil.getResultTypesAsString(template.getSupportedResultTypes());
        html += "supports: " + StringUtils.defaultIfEmpty(supportedResultTypes, "-");
        html += "</font></p>\n";

        html += "<h2>" + template.getName() + "</h2>\n";

        String description = StringUtils.nullToEmpty(template.getDescription());
        boolean looksLikeHtml = description.trim().startsWith("<");
        html += looksLikeHtml ? "<font size='+1'>" + description + "</font>" : "<pre>"+description+"</pre>";
        return html;
    }

    protected Image getCachedImage(ChartTemplate template, String imageName) {
        String imagePath = template.getOriginFolder() + "/" + imageName;

        Image image = imageRegistry.get(imagePath);
        if (image == null) {
            image = loadImage(imagePath);
            if (image == null)
                return ImageFactory.global().getImage(ImageFactory.UNKNOWN);
            imageRegistry.put(imagePath, image);

        }
        return image;
    }

    protected Image loadImage(String imagePath) {
        InputStream stream = null;
        try {
            stream = getStream(imagePath);
            if (stream == null)
                return null;
            ImageData imageData = new ImageData(stream);
            return new Image(Display.getCurrent(), imageData);
        } catch (Exception e) {
            ScavePlugin.logError("Cannot load image from '"+imagePath+"' for chart template description", e);
            return null;
        } finally {
            try {if (stream != null) stream.close();} catch (IOException ex) {}
        }
    }

    protected InputStream getStream(String imagePath) throws IOException, CoreException {
        if (imagePath.startsWith("plugin:")) {
            Bundle bundle = Platform.getBundle("org.omnetpp.scave.templates");
            URL resource = bundle.getResource(imagePath.substring(7));
            InputStream stream = (resource == null) ? null : resource.openStream();
            if (stream == null)
                throw new IOException("Could not read resource file: " + imagePath);
            return stream;
        } else
            return ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(imagePath)).getContents(true);
    }

    protected ChartTemplate getTableSelection() {
        Object firstElement = ((IStructuredSelection)tableViewer.getSelection()).getFirstElement();
        if (firstElement instanceof ChartTemplate)
            return (ChartTemplate)firstElement;
        return null;
    }

    @Override
    public boolean close() {
        imageRegistry.dispose();

        int w0 = sashForm.getChildren()[0].getBounds().width;
        int w1 = sashForm.getChildren()[1].getBounds().width;
        getDialogBoundsSettings().put("sash.weight0", Integer.toString(w0));
        getDialogBoundsSettings().put("sash.weight1", Integer.toString(w1));

        return super.close();
    }

    /**
     * Returns the section to insert the keys into.
     */
    public ChartTemplate getSelectedTemplate() {
        return selectedTemplate;
    }
}
