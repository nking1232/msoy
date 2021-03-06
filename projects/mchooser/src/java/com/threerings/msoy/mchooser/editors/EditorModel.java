//
// $Id$

package com.threerings.msoy.mchooser.editors;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.google.common.collect.Lists;

/**
 * Represents the state of the image editor.
 */
public class EditorModel
{
    /**
     * Adds a listener for model changes.
     */
    public void addChangeListener (ChangeListener listener)
    {
        _listeners.add(listener);
    }

    /**
     * Removes a listener of model changes.
     */
    public void removeChangeListener (ChangeListener listener)
    {
        _listeners.remove(listener);
    }

    public void reset (Iterable<EditorOp> ops)
    {
        // reset our data
        _image = cloneImage(_original);
        _offset.move(0, 0);
        _rotation = 0;
        _scaleX = _scaleY = 1;

        // reapply the requested operations
        if (ops != null) {
            _silent = true;
            for (EditorOp op : ops) {
                op.apply(this);
            }
            _silent = false;
        }
        fireStateChanged();
    }

    public BufferedImage getImage ()
    {
        return _image;
    }

    public byte[] getImageBytes ()
        throws IOException
    {
        // generate our rotated, scaled, cropped image
        int width = (int)Math.round(_image.getWidth() * _scaleX);
        int height = (int)Math.round(_image.getHeight() * _scaleY);
        BufferedImage image = new BufferedImage(width, height, _image.getType());
        Graphics2D gfx = image.createGraphics();
        try {
            gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                 RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            gfx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gfx.rotate(_rotation);
            gfx.scale(_scaleX, _scaleY);
            gfx.drawImage(_image, null, null);
        } finally {
            gfx.dispose();
        }

        // determine if we want a transparent or non-transparent image
        String format = (image.getTransparency() == BufferedImage.OPAQUE) ? "JPG" : "PNG";
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(image, format, bout);
        return bout.toByteArray();
    }

    public void setImage (BufferedImage image)
    {
        _original = image;
        reset(null);
        fireStateChanged();
    }

    public Point getOffset ()
    {
        return new Point(_offset);
    }

    public void setOffset (Point offset)
    {
        if (!offset.equals(_offset)) {
            _offset.setLocation(offset);
            fireStateChanged();
        }
    }

    public double getRotation ()
    {
        return _rotation;
    }

    public void setRotation (double rotation)
    {
        if (_rotation != rotation) {
            _rotation = rotation;
            fireStateChanged();
        }
    }

    public double getScaleX ()
    {
        return _scaleX;
    }

    public double getScaleY ()
    {
        return _scaleY;
    }

    public void setScale (double scaleX, double scaleY)
    {
        if (_scaleX != scaleX || _scaleY != scaleY) {
            _scaleX = scaleX;
            _scaleY = scaleY;
            fireStateChanged();
        }
    }

    public void paint (Graphics2D gfx)
    {
        gfx.translate(_offset.x, _offset.y);
        gfx.rotate(_rotation);
        gfx.scale(_scaleX, _scaleY);
        gfx.drawImage(_image, null, null);
    }

    protected void fireStateChanged ()
    {
        if (!_silent) {
            ChangeEvent event = new ChangeEvent(this);
            for (ChangeListener listener : _listeners) {
                listener.stateChanged(event);
            }
        }
    }

    protected BufferedImage cloneImage (BufferedImage image)
    {
        return image; // TODO
    }

    protected BufferedImage _original;

    protected BufferedImage _image;
    protected Point _offset = new Point();
    protected double _rotation;
    protected double _scaleX = 1, _scaleY = 1;

    protected boolean _silent;
    protected List<ChangeListener> _listeners = Lists.newArrayList();
}
