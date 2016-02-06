/*
 * ChunkOutputWidget.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.source.editors.text;

import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.Debug;
import org.rstudio.core.client.VirtualConsole;
import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.widget.PreWidget;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.rmarkdown.model.RmdChunkOutput;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsolePromptHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteErrorEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteErrorHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteOutputEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteOutputHandler;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteInputEvent;
import org.rstudio.studio.client.workbench.views.console.events.ConsoleWriteInputHandler;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Widget;

public class ChunkOutputWidget extends Composite
                               implements ConsoleWriteOutputHandler,
                                          ConsoleWriteErrorHandler,
                                          ConsolePromptHandler,
                                          ConsoleWriteInputHandler
{

   private static ChunkOutputWidgetUiBinder uiBinder = GWT
         .create(ChunkOutputWidgetUiBinder.class);

   interface ChunkOutputWidgetUiBinder
         extends UiBinder<Widget, ChunkOutputWidget>
   {
      
   }

   public ChunkOutputWidget(String chunkId,
         CommandWithArg<Integer> onRenderCompleted,
         final Command onChunkCleared)
   {
      chunkId_ = chunkId;
      initWidget(uiBinder.createAndBindUi(this));
      applyCachedEditorStyle();
      
      onRenderCompleted_ = onRenderCompleted;
      
      interrupt_.addClickHandler(new ClickHandler()
      {
         @Override
         public void onClick(ClickEvent arg0)
         {
            // TODO: fire interrupt event
         }
      });
      
      DOM.sinkEvents(clear_.getElement(), 
            Event.ONCLICK | Event.ONMOUSEOVER | Event.ONMOUSEOUT);
      DOM.setEventListener(clear_.getElement(), new EventListener()
      {
         @Override
         public void onBrowserEvent(Event evt)
         {
            switch(DOM.eventGetType(evt))
            {
            case Event.ONCLICK:
               Debug.devlog("clear was clicked!");
               if (state_ == CONSOLE_READY ||
                   state_ == CONSOLE_EXECUTING)
               {
                  destroyConsole();
               }
               onChunkCleared.execute();
               break;

            case Event.ONMOUSEOVER:
               clear_.getElement().getStyle().setOpacity(1);
               break;
            
            case Event.ONMOUSEOUT:
               clear_.getElement().getStyle().clearOpacity();
               break;
            };
         }
      });

      interrupt_.setResource(RStudioGinjector.INSTANCE.getCommands()
            .interruptR().getImageResource());
   }

   public void showChunkOutput(RmdChunkOutput output)
   {
      // clean up old frame if needed
      if (frame_ != null)
         frame_.removeFromParent();

      frame_ = new ChunkOutputFrame();
      frame_.getElement().getStyle().setHeight(100, Unit.PCT);
      frame_.getElement().getStyle().setWidth(100, Unit.PCT);
      root_.add(frame_);

      frame_.loadUrl(output.getUrl(), new Command() 
      {
         @Override
         public void execute()
         {
            if (state_ != CHUNK_RENDERING)
               return;
            state_ = CHUNK_RENDERED;
            applyCachedEditorStyle();
            showReadyState();
            onRenderCompleted_.execute(
                  frame_.getDocument().getDocumentElement().getScrollHeight());
         };
      });
      state_ = CHUNK_RENDERING;
   }
   
   @Override
   public void onConsoleWriteOutput(ConsoleWriteOutputEvent event)
   {
      if (state_ != CONSOLE_EXECUTING || event.getConsole() != chunkId_)
         return;
      renderConsoleOutput(event.getOutput(), null);
   }
   
   @Override
   public void onConsoleWriteError(ConsoleWriteErrorEvent event)
   {
      if (state_ != CONSOLE_EXECUTING || event.getConsole() != chunkId_)
         return;
      renderConsoleOutput(event.getError(), 
            RStudioGinjector.INSTANCE.getUIPrefs().getThemeErrorClass());
   }
   
   @Override
   public void onConsoleWriteInput(ConsoleWriteInputEvent event)
   {
      if (state_ != CONSOLE_EXECUTING || event.getConsole() != chunkId_)
         return;
      renderConsoleOutput("> " + event.getInput(), "ace_keyword");
   }

   @Override
   public void onConsolePrompt(ConsolePromptEvent event)
   {
      if (state_ != CONSOLE_EXECUTING)
         return;
      state_ = CONSOLE_READY;
      unregisterConsoleEvents();
      showReadyState();
   }

   private void renderConsoleOutput(String text, String clazz)
   {
      vconsole_.submitAndRender(text, clazz,
            console_.getElement());
      onRenderCompleted_.execute(console_.getElement().getOffsetHeight());
   }

   public void setChunkExecuting()
   {
      if (state_ == CHUNK_EXECUTING)
         return;
      if (state_ == CONSOLE_READY)
         destroyConsole();
      state_ = CHUNK_EXECUTING;
      showBusyState();
   }
   
   public void applyCachedEditorStyle()
   {
      if (!isEditorStyleCached())
         return;
      Style frameStyle = getElement().getStyle();
      frameStyle.setBorderColor(s_outlineColor);
      if (state_ == CHUNK_RENDERED)
      {
         Style bodyStyle = frame_.getDocument().getBody().getStyle();
         bodyStyle.setColor(s_color);
      }
   }
   
   public void showConsoleCode(String code)
   {
      if (state_ != CONSOLE_READY &&
          state_ != CONSOLE_EXECUTING)
      {
         initConsole();
         state_ = CONSOLE_READY;
      }
      if (state_ == CONSOLE_READY)
      {
         showBusyState();
         registerConsoleEvents();
         state_ = CONSOLE_EXECUTING;
      }
      onRenderCompleted_.execute(console_.getElement().getOffsetHeight());
   }
   
   public static void cacheEditorStyle(Element editorContainer, 
         Style editorStyle)
   {
      s_backgroundColor = editorStyle.getBackgroundColor();
      s_color = editorStyle.getColor();
      s_outlineColor = DomUtils.extractCssValue("ace_print-margin", 
            "backgroundColor");
      JsArrayString classes = JsArrayString.createArray().cast();
      classes.push("ace_marker-layer");
      classes.push("ace_foreign_line");
      s_busyColor = DomUtils.extractCssValue(classes, "backgroundColor");
   }
   
   private void initConsole()
   {
      if (vconsole_ == null)
         vconsole_ = new VirtualConsole();
      if (console_ == null)
      {
         console_ = new PreWidget();
         console_.getElement().getStyle().setMarginTop(0, Unit.PX);
      }
      // remove the frame if it exists
      if (frame_ != null)
         frame_.removeFromParent();
      
      // attach the console
      root_.add(console_);
   }
   
   private void destroyConsole()
   {
      vconsole_.clear();
      console_.removeFromParent();
      console_ = null;
   }
   
   private void registerConsoleEvents()
   {
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.addHandler(ConsoleWriteOutputEvent.TYPE, this);
      events.addHandler(ConsoleWriteErrorEvent.TYPE, this);
      events.addHandler(ConsoleWriteInputEvent.TYPE, this);
      events.addHandler(ConsolePromptEvent.TYPE, this);
   }

   private void unregisterConsoleEvents()
   {
      EventBus events = RStudioGinjector.INSTANCE.getEventBus();
      events.removeHandler(ConsoleWriteOutputEvent.TYPE, this);
      events.removeHandler(ConsoleWriteErrorEvent.TYPE, this);
      events.removeHandler(ConsoleWriteInputEvent.TYPE, this);
      events.removeHandler(ConsolePromptEvent.TYPE, this);
   }
   
   public static boolean isEditorStyleCached()
   {
      return s_backgroundColor != null &&
             s_color != null &&
             s_outlineColor != null;
   }
   
   private void showBusyState()
   {
      getElement().getStyle().setBackgroundColor(s_busyColor);
      clear_.setVisible(false);
      interrupt_.setVisible(true);
   }

   private void showReadyState()
   {
      getElement().getStyle().setBackgroundColor(s_backgroundColor);
      clear_.setVisible(true);
      interrupt_.setVisible(false);
   }
   
   @UiField Image interrupt_;
   @UiField Image clear_;
   @UiField HTMLPanel root_;
   
   private ChunkOutputFrame frame_;
   private PreWidget console_;
   private VirtualConsole vconsole_;
   
   private int state_ = CHUNK_EMPTY;
   
   private CommandWithArg<Integer> onRenderCompleted_;
   private final String chunkId_;

   private static String s_outlineColor    = null;
   private static String s_backgroundColor = null;
   private static String s_color           = null;
   private static String s_busyColor       = null;
   
   public final static int CHUNK_EMPTY       = 0;
   public final static int CHUNK_EXECUTING   = 1;
   public final static int CHUNK_RENDERING   = 2;
   public final static int CHUNK_RENDERED    = 3;
   public final static int CONSOLE_READY     = 4;
   public final static int CONSOLE_EXECUTING = 5;
}
