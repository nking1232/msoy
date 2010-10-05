//
// $Id: $

package client.adminz.config;

import com.threerings.msoy.admin.config.gwt.ConfigField;
import com.threerings.msoy.admin.config.gwt.ConfigField.FieldType;

import com.google.gwt.dom.client.Style;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

import client.ui.MsoyUI;

/**
 *
 */
public abstract class ConfigFieldEditor
{
    public static ConfigFieldEditor getEditorFor (ConfigField field)
    {
        return new StringFieldEditor(field);
    }

    protected static class StringFieldEditor extends ConfigFieldEditor
    {
        public StringFieldEditor (ConfigField field)
        {
            super(field);
        }

        @Override
        protected Widget buildWidget (ConfigField field)
        {
            _box = new TextBox();
            _box.setStyleName("configStringEditor");
            _box.setVisibleLength(40);
            _box.setText(field.valStr);
            _box.addChangeHandler(new ChangeHandler() {
                @Override public void onChange (ChangeEvent changeEvent) {
                    updateModificationState();
                }
            });
            return _box;
        }


        @Override
        public ConfigField getModifiedField ()
        {
            Object newValue = textToValue(_box.getText().trim(), _field.type);
            String newValStr = (newValue != null) ? newValue.toString() : null;
            if (newValStr == null || newValStr.equals(_field.valStr)) {
                return null;
            }
            return new ConfigField(_field.name, _field.type, newValStr);
        }

        @Override
        protected void resetField ()
        {
            _box.setText(_field.valStr);
        }

        protected static Object textToValue (String text, FieldType type)
        {
            switch(type) {
            case INTEGER:
                return new Integer(text);
            case SHORT:
                return new Short(text);
            case BYTE:
                return new Byte(text);
            case LONG:
                return new Long(text);
            case FLOAT:
                return new Float(text);
            case DOUBLE:
                return new Double(text);
            case BOOLEAN:
                return new Boolean(text);
            case STRING:
                return text;
            }
            return null;
        }

        protected TextBox _box;
    }

    public ConfigFieldEditor (ConfigField field)
    {
        _field = field;

        _value = buildWidget(field);
        _name = MsoyUI.createLabel(field.name, "fieldName");
        _reset = MsoyUI.createCloseButton(new ClickHandler() {
            public void onClick (ClickEvent event) {
                resetField();
                updateModificationState();
            }
        });
        _reset.setVisible(false);
    }

    protected void updateModificationState ()
    {
        Style style = _value.getElement().getStyle();
        if (getModifiedField() != null) {
            style.setBackgroundColor("red");
            _reset.setVisible(true);

        } else {
            style.clearBackgroundColor();
            _reset.setVisible(false);
        }
    }

    public Widget getNameWidget ()
    {
        return _name;
    }

    public Widget getValueWidget ()
    {
        return _value;
    }

    public Widget getResetWidget ()
    {
        return _reset;
    }

    public abstract ConfigField getModifiedField ();

    protected abstract Widget buildWidget (ConfigField field);
    protected abstract void resetField ();

    protected Widget _name, _value, _reset;
    protected ConfigField _field;
}