/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.sync;

 
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;

import org.eclipse.compare.BufferedContent;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.NavigationAction;
import org.eclipse.compare.internal.CompareUIPlugin;
import org.eclipse.compare.structuremergeviewer.DiffContainer;
import org.eclipse.compare.structuremergeviewer.DiffElement;
import org.eclipse.compare.structuremergeviewer.DiffTreeViewer;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.IDiffContainer;
import org.eclipse.compare.structuremergeviewer.IDiffElement;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.team.core.sync.ILocalSyncElement;
import org.eclipse.team.core.sync.IRemoteSyncElement;
import org.eclipse.team.internal.ui.IHelpContextIds;
import org.eclipse.team.internal.ui.Policy;
import org.eclipse.team.internal.ui.TeamUIPlugin;
import org.eclipse.team.internal.ui.UIConstants;
import org.eclipse.team.ui.TeamImages;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.help.WorkbenchHelp;
import org.eclipse.ui.views.navigator.ResourceNavigator;

/**
 * <b>Note:</b> This class/interface is part of an interim API that is still under 
 * development and expected to change significantly before reaching stability. 
 * It is being made available at this early stage to solicit feedback from pioneering 
 * adopters on the understanding that any code that uses this API will almost 
 * certainly be broken (repeatedly) as the API evolves.
 * 
 * This viewer adds a custom filter and some merge actions.
 * Note this is a layer breaker and needs to be refactored. Viewers should
 * not contain references to workbench actions. Actions should be contributed
 * by the view.
 */
public abstract class CatchupReleaseViewer extends DiffTreeViewer {
	
	class ShowInNavigatorAction extends Action implements ISelectionChangedListener {
		IViewSite viewSite;
		public ShowInNavigatorAction(IViewSite viewSite, String title) {
			super(title, null);
			this.viewSite = viewSite;
		}
		public void run() {
			showSelectionInNavigator(viewSite);
		}
		public void selectionChanged(SelectionChangedEvent event) {
			IStructuredSelection selection = (IStructuredSelection)event.getSelection();
			if (selection.size() == 0) {
				setEnabled(false);
				return;
			}
			for (Iterator iter = selection.iterator(); iter.hasNext();) {
				ITeamNode node = (ITeamNode)iter.next();
				if(!node.getResource().isAccessible()) {
					setEnabled(false);
					return;
				}
			}
			setEnabled(true);
		}
	};
	
	/**
	 * This filter hides all empty categories tree nodes.
	 */
	class CategoryFilter extends ViewerFilter {
		static final int SHOW_INCOMING = 1;
		static final int SHOW_OUTGOING = 2;
		static final int SHOW_CONFLICTS = 4;
		static final int SHOW_PSEUDO_CONFLICTS = 8;

		private int showMask = 0;
		
		CategoryFilter(int showMask) {
			// Mask for all categories to show
			this.showMask = showMask;
		}
		int getMask() {
			return showMask;
		}
		void setMask(int mask) {
			this.showMask = mask;
		}
		public boolean select(Viewer viewer, Object parentElement, Object element) {
			// If this element has visible children, always show it.
			// This is not great -- O(n^2) filtering
			if (hasFilteredChildren(element)) {
				return true;
			}
			if (element instanceof ITeamNode) {
				// Filter out pseudo conflicts if requested
				int kind = ((ITeamNode)element).getKind();
				if ((showMask & SHOW_PSEUDO_CONFLICTS) == 0 && (kind & IRemoteSyncElement.PSEUDO_CONFLICT) != 0) {
					return false;
				}
				int change = ((ITeamNode)element).getKind() & IRemoteSyncElement.CHANGE_MASK;
				int direction = ((ITeamNode)element).getChangeDirection();
				switch (direction) {
					case ITeamNode.INCOMING:
						return (showMask & SHOW_INCOMING) != 0;
					case ITeamNode.OUTGOING:
						return (showMask & SHOW_OUTGOING) != 0;
					case Differencer.CONFLICTING:
						return (showMask & SHOW_CONFLICTS) != 0;
					default:
						return change != 0;
				}
			}
			// No children are visible, and this folder has no changes, so don't show it.
			return false;
		}
		public boolean isFilterProperty(Object element, String property) {
			return property.equals(PROP_KIND);
		}
	}
	
	class FilterAction extends Action {
		/** 
		 * Must subclass constructor to make it accessible to container class
		 */
		FilterAction(String title, ImageDescriptor image) {
			super(title, image);
		}
		public void run() {
			updateFilters();
		}
	}
	class SyncSorter extends ViewerSorter {
		public int compare(Viewer viewer, Object e1, Object e2) {
			boolean oneIsFile = e1 instanceof TeamFile;
			boolean twoIsFile = e2 instanceof TeamFile;
			if (oneIsFile != twoIsFile) {
				return oneIsFile ? 1 : -1;
			}
			return super.compare(viewer, e1, e2);
		}
	}
	
	class RemoveFromTreeAction extends Action {
		public RemoveFromTreeAction(String title, ImageDescriptor image) {
			super(title, image);
		}
		public void run() {
			ISelection s = getSelection();
			if (!(s instanceof IStructuredSelection) || s.isEmpty()) {
				return;
			}
			// mark all selected nodes as in sync
			for (Iterator it = ((IStructuredSelection)s).iterator(); it.hasNext();) {
				Object element = it.next();
				setAllChildrenInSync((IDiffElement)element);
			}
			refresh();
		}
		public void update() {
			// Update action enablement
			setEnabled(!getSelection().isEmpty());
		}
	}
	class ExpandAllAction extends Action {
		public ExpandAllAction(String title, ImageDescriptor image) {
			super(title, image);
		}
		public void run() {
			expandSelection();
		}
		public void update() {
			setEnabled(!getSelection().isEmpty());
		}
	}
	class OpenAction extends Action {
		public OpenAction(String title, ImageDescriptor image) {
			super(title, image);
		}
		public void run() {
			openSelection();
		}
		public void update() {
			ISelection selection = getSelection();
			if (selection instanceof IStructuredSelection) {
				IStructuredSelection ss = (IStructuredSelection)selection;
				if (ss.size() == 1) {
					Object element = ss.getFirstElement();
					setEnabled(element instanceof TeamFile);
					return;
				}
			}
			setEnabled(false);
		}
	}
	
	// The current sync mode
	private int syncMode = SyncView.SYNC_NONE;
	
	// Actions
	private FilterAction showIncoming;
	private FilterAction showOutgoing;
	private FilterAction showOnlyConflicts;
	private Action refresh;
	private OpenAction open;
	private ExpandAllAction expandAll;
	private RemoveFromTreeAction removeFromTree;
	private ShowInNavigatorAction showInNavigator;
	private Action ignoreWhiteSpace;
	private Action toggleGranularity;
	
	private NavigationAction showPrevious;
	private NavigationAction showNext;
	
	// Property constant for diff mode kind
	static final String PROP_KIND = "team.ui.PropKind"; //$NON-NLS-1$

	private Action copyAllRightToLeft;

	private boolean compareFileContents = false;
	
	/**
	 * Creates a new catchup/release viewer.
	 */
	protected CatchupReleaseViewer(Composite parent, SyncCompareInput model) {
		super(parent, model.getCompareConfiguration());
		setSorter(new SyncSorter());
		initializeActions(model);
	}
	
	/**
	 * Contributes actions to the provided toolbar
	 */
	void contributeToActionBars(IActionBars actionBars) {
		IToolBarManager toolBar = actionBars.getToolBarManager();
	
		toolBar.add(new Separator());
		toolBar.add(showOnlyConflicts);
	
		toolBar.add(new Separator());
		toolBar.add(showNext);
		toolBar.add(showPrevious);
		
		// Drop down menu
		IMenuManager menu = actionBars.getMenuManager();
		if (syncMode == SyncView.SYNC_BOTH) {
			menu.add(showIncoming);
			menu.add(showOutgoing);
		}
		menu.add(toggleGranularity);
		menu.add(ignoreWhiteSpace);
		menu.add(refresh);
	}
	
	/**
	 * Contributes actions to the popup menu.
	 */
	protected void fillContextMenu(IMenuManager manager) {
		open.update();
		manager.add(open);
		manager.add(new Separator());
		expandAll.update();
		manager.add(expandAll);
		removeFromTree.update();
		manager.add(removeFromTree); 
		if (showInNavigator != null) {
			manager.add(showInNavigator);
		}
		if (syncMode == SyncView.SYNC_COMPARE) {
			if(copyAllRightToLeft.isEnabled()) {
				manager.add(copyAllRightToLeft);
			}
		}
	}
	
	protected void openSelection() {
		ISelection selection = getSelection();
		if (selection instanceof IStructuredSelection) {
			Iterator elements = ((IStructuredSelection)selection).iterator();
			while (elements.hasNext()) {
				Object next = elements.next();
				openSelection(next);
			}
		}
	}
	
	/**
	 * Method openSelection.
	 * @param next
	 */
	private void openSelection(Object next) {
		if (next instanceof TeamFile) {
			handleOpen(null);
		}
	}
	
	/**
	 * Expands to infinity all items in the selection.
	 */
	protected void expandSelection() {
		ISelection selection = getSelection();
		if (selection instanceof IStructuredSelection) {
			Iterator elements = ((IStructuredSelection)selection).iterator();
			while (elements.hasNext()) {
				Object next = elements.next();
				expandToLevel(next, ALL_LEVELS);
			}
		}
	}
	
	protected int getSyncMode() {
		return syncMode;
	}
	
	/**
	 * Returns true if the given element has filtered children, and false otherwise.
	 */
	protected boolean hasFilteredChildren(Object element) {
		return getFilteredChildren(element).length > 0;
	}
	
	/**
	 * Creates the actions for this viewer.
	 */
	private void initializeActions(final SyncCompareInput diffModel) {
		// Mask actions
		ImageDescriptor image = TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_INCOMING_ENABLED);
		showIncoming = new FilterAction(Policy.bind("CatchupReleaseViewer.showIncomingAction"), image); //$NON-NLS-1$
		showIncoming.setToolTipText(Policy.bind("CatchupReleaseViewer.showIncomingAction")); //$NON-NLS-1$
		showIncoming.setDisabledImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_INCOMING_DISABLED));
		showIncoming.setHoverImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_INCOMING));
		
		image = TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_OUTGOING_ENABLED);
		showOutgoing = new FilterAction(Policy.bind("CatchupReleaseViewer.showOutgoingAction"), image); //$NON-NLS-1$
		showOutgoing.setToolTipText(Policy.bind("CatchupReleaseViewer.showOutgoingAction")); //$NON-NLS-1$
		showOutgoing.setDisabledImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_OUTGOING_DISABLED));
		showOutgoing.setHoverImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_OUTGOING));
			
		//show only conflicts is not a HideAction because it doesnt flip bits, it sets an exact mask
		image = TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_CONFLICTING_ENABLED);
		showOnlyConflicts = new FilterAction(Policy.bind("CatchupReleaseViewer.showOnlyConflictsAction"), image); //$NON-NLS-1$
		showOnlyConflicts.setToolTipText(Policy.bind("CatchupReleaseViewer.showOnlyConflictsAction")); //$NON-NLS-1$
		showOnlyConflicts.setDisabledImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_CONFLICTING_DISABLED));
		showOnlyConflicts.setHoverImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_DLG_SYNC_CONFLICTING));

		//refresh action
		image = TeamImages.getImageDescriptor(UIConstants.IMG_REFRESH_ENABLED);
		refresh = new Action(Policy.bind("CatchupReleaseViewer.refreshAction"), image) { //$NON-NLS-1$
			public void run() {
				diffModel.refresh();
			}
		};
		refresh.setToolTipText(Policy.bind("CatchupReleaseViewer.refreshAction")); //$NON-NLS-1$
		refresh.setDisabledImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_REFRESH_DISABLED));
		refresh.setHoverImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_REFRESH));
		
		// Open Action
		open = new OpenAction(Policy.bind("CatchupReleaseViewer.open"), null); //$NON-NLS-1$
		WorkbenchHelp.setHelp(open, IHelpContextIds.OPEN_ACTION);
		
		// Expand action
		expandAll = new ExpandAllAction(Policy.bind("CatchupReleaseViewer.expand"), null); //$NON-NLS-1$
		WorkbenchHelp.setHelp(expandAll, IHelpContextIds.EXPANDALL_ACTION);
		
		// Toggle granularity
		image = TeamImages.getImageDescriptor(UIConstants.IMG_CONTENTS_ENABLED);
		toggleGranularity = new Action(Policy.bind("CatchupReleaseViewer.Compare_File_Contents_1"), image) { //$NON-NLS-1$
			public void run() {
				compareFileContents = isChecked();
				diffModel.setSyncGranularity(compareFileContents ? ILocalSyncElement.GRANULARITY_CONTENTS : ILocalSyncElement.GRANULARITY_TIMESTAMP);
				updateFilters();
			}
		};
		compareFileContents = diffModel.getSyncGranularity() != IRemoteSyncElement.GRANULARITY_TIMESTAMP;
		toggleGranularity.setChecked(compareFileContents);
		toggleGranularity.setDisabledImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_CONTENTS_DISABLED));
		toggleGranularity.setHoverImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_CONTENTS));
		
		removeFromTree = new RemoveFromTreeAction(Policy.bind("CatchupReleaseViewer.removeFromView"), null); //$NON-NLS-1$
		WorkbenchHelp.setHelp(removeFromTree, IHelpContextIds.REMOVE_ACTION);
		
		copyAllRightToLeft = new Action(Policy.bind("CatchupReleaseViewer.copyAllRightToLeft"), null) { //$NON-NLS-1$
			public void run() {
				ISelection s = getSelection();
				if (!(s instanceof IStructuredSelection) || s.isEmpty()) {
					return;
				}
				// action is only enabled for 1 element. the for loop				
				final Object element =  ((IStructuredSelection)s).getFirstElement();
				if(element instanceof DiffElement) {
					try {
						new ProgressMonitorDialog(getTree().getShell()).run(true /* fork */, true /* cancellable */, new IRunnableWithProgress() {
							public void run(IProgressMonitor monitor)
								throws InvocationTargetException, InterruptedException {
									try {
										monitor.beginTask(null, 1000);
										monitor.setTaskName(Policy.bind("CatchupReleaseViewer.Copying_right_contents_into_workspace_2")); //$NON-NLS-1$
										ResourcesPlugin.getWorkspace().run(new IWorkspaceRunnable() {
											public void run(IProgressMonitor monitor) throws CoreException {
												try {
													monitor.beginTask(null, 100);
													copyAllRightToLeft((DiffElement)element, monitor);
												} finally {
													monitor.done();
												}
											}
										}, Policy.subInfiniteMonitorFor(monitor, 1000));
									} catch(CoreException e) {
										throw new InvocationTargetException(e);
									} finally {
										monitor.done();
									}
								}
						});
					} catch(InvocationTargetException e) {
						ErrorDialog.openError(TeamUIPlugin.getPlugin().getWorkbench().getActiveWorkbenchWindow().getShell(), Policy.bind("CatchupReleaseViewer.errorCopyAllRightToLeft"), null, null); //$NON-NLS-1$
					} catch(InterruptedException e) {
					}														
				}
				refresh();				
			}
			public boolean isEnabled() {
				ISelection s = getSelection();
				if (!(s instanceof IStructuredSelection) || s.isEmpty()) {
					return false;
				}
				return ((IStructuredSelection)s).size() == 1;
			}
		};
		
		// Show in navigator
		if (diffModel.getViewSite() != null) {
			showInNavigator = new ShowInNavigatorAction(diffModel.getViewSite(), Policy.bind("CatchupReleaseViewer.showInNavigator")); //$NON-NLS-1$
			WorkbenchHelp.setHelp(showInNavigator, IHelpContextIds.NAVIGATOR_SHOW_ACTION);
			addSelectionChangedListener(showInNavigator);
		}
		
		// Ignore white space
		image = TeamImages.getImageDescriptor(UIConstants.IMG_IGNORE_WHITESPACE_ENABLED);
		ignoreWhiteSpace = new Action(Policy.bind("CatchupReleaseViewer.ignoreWhiteSpace"), image) { //$NON-NLS-1$
			public void run() {
				diffModel.setIgnoreWhitespace(isChecked());
			}
		};
		ignoreWhiteSpace.setId("team.ignoreWhiteSpace"); //$NON-NLS-1$
		boolean ignore = CompareUIPlugin.getDefault().getPreferenceStore().getBoolean(CompareConfiguration.IGNORE_WHITESPACE);
		ignoreWhiteSpace.setChecked(ignore);
		ignoreWhiteSpace.setDisabledImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_IGNORE_WHITESPACE_DISABLED));
		ignoreWhiteSpace.setHoverImageDescriptor(TeamImages.getImageDescriptor(UIConstants.IMG_IGNORE_WHITESPACE));
		
		// Show next and previous change
		showNext = new NavigationAction(true);
		showPrevious = new NavigationAction(false);
		showNext.setCompareEditorInput(diffModel);
		showPrevious.setCompareEditorInput(diffModel);
		
		// Add a double-click listener for expanding/contracting
		getTree().addListener(SWT.MouseDoubleClick, new Listener() {
			public void handleEvent(Event e) {
				mouseDoubleClicked(e);
			}
		});
	
		// Add an F5 listener for refresh
		getTree().addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.F5) {
					diffModel.refresh();
				}
			}
		});
	
		// Set an initial filter -- show all changes
		showIncoming.setChecked(true);
		showOutgoing.setChecked(true);
		showOnlyConflicts.setChecked(false);
		setFilters(CategoryFilter.SHOW_INCOMING| CategoryFilter.SHOW_CONFLICTS | CategoryFilter.SHOW_OUTGOING);
	}

	/**
	 * Method setAllChildrenInSync.
	 * @param iDiffElement
	 */
	private void setAllChildrenInSync(IDiffElement element) {
		if(element instanceof DiffContainer) {
			DiffContainer container = (DiffContainer)element;
			IDiffElement[] children = container.getChildren();
			for (int i = 0; i < children.length; i++) {
				setAllChildrenInSync(children[i]);
			}			
		}
		((DiffElement)element).setKind(IRemoteSyncElement.IN_SYNC);
	}
	
	protected void copyAllRightToLeft(IDiffElement element, IProgressMonitor monitor) throws CoreException {
		Policy.checkCanceled(monitor);
		
		if(element instanceof DiffContainer) {
			DiffContainer container = (DiffContainer)element;
			IDiffElement[] children = container.getChildren();
			for (int i = 0; i < children.length; i++) {
				copyAllRightToLeft(children[i], monitor);
			}
		} else if(element instanceof TeamFile) {
			TeamFile file = (TeamFile)element;
			if(file.getKind() != IRemoteSyncElement.IN_SYNC) {
				monitor.subTask(
					Policy.bind("CatchupReleaseViewer.MakingLocalLikeRemote",  //$NON-NLS-1$
						Policy.toTruncatedPath(file.getMergeResource().getResource().getFullPath(), 3)));
				file.setProgressMonitor(Policy.subNullMonitorFor(monitor));

				if(file.getRight() == null || file.getLeft() == null) {
					file.copy(false /* right to left */);
				} 
				ITypedElement te = file.getLeft();
				ITypedElement rte = file.getRight();
				if(te instanceof IEditableContent) {
					IEditableContent editable = (IEditableContent)te;
					if(editable.isEditable()) {
						if(rte instanceof BufferedContent) {
							editable.setContent(((BufferedContent)rte).getContent());
						}
					}
				}
				file.setProgressMonitor(null);
				monitor.worked(1);
			}
		}
	}
	
	/*
	 * Method declared on ContentViewer.
	 */
	protected void inputChanged(Object input, Object oldInput) {
		super.inputChanged(input, oldInput);
		// Update the refresh action
		if (refresh != null) {
			Tree tree = getTree();
			if (tree != null) {
				refresh.setEnabled(input != null);
			}
		}
	}

	/**
	 * Shows the selected resource(s) in the resource navigator.
	 */
	private void showSelectionInNavigator(IViewSite viewSite) {
		ISelection selection = getSelection();
		if (!(selection instanceof IStructuredSelection)) {
			return;
		}
		// Create a selection of IResource objects
		Object[] selected = ((IStructuredSelection)selection).toArray();
		IResource[] resources = new IResource[selected.length];
		for (int i = 0; i < selected.length; i++) {
			resources[i] = ((ITeamNode)selected[i]).getResource();
		}
		ISelection resourceSelection = new StructuredSelection(resources);
		
		// Show the resource selection in the navigator
		try {
			IViewPart part = viewSite.getPage().showView(IPageLayout.ID_RES_NAV);
			if (part instanceof ResourceNavigator) {
				((ResourceNavigator)part).selectReveal(resourceSelection);
			}
		} catch (PartInitException e) {
			TeamUIPlugin.log(e);
		}
	}
	
	/**
	 * The mouse has been double-clicked in the tree, perform appropriate
	 * behaviour.
	 */
	private void mouseDoubleClicked(Event e) {
		// Only act on single selection
		ISelection selection = getSelection();
		if (selection instanceof IStructuredSelection) {
			IStructuredSelection structured = (IStructuredSelection)selection;
			if (structured.size() == 1) {
				Object first = structured.getFirstElement();
				if (first instanceof IDiffContainer) {
					// Try to expand/contract
					setExpandedState(first, !getExpandedState(first));
				}
			}
		}
	}
	
	/**
	 * @see org.eclipse.jface.viewers.StructuredViewer#handleOpen(SelectionEvent)
	 */
	protected void handleOpen(SelectionEvent event) {
		ISelection selection = getSelection();
		if (!selection.isEmpty() && selection instanceof IStructuredSelection) {
			IStructuredSelection structured = (IStructuredSelection)selection;
			Object selected = structured.getFirstElement();
			if (selected instanceof TeamFile) {
				updateLabels(((TeamFile)selected).getMergeResource());
			}
		}
		super.handleOpen(event);
	}
	
	/**
	 * Subclasses may override to provide different labels for the compare configuration.
	 */
	protected void updateLabels(MergeResource resource) {
		resource.setLabels(getCompareConfiguration());
	}
	
	/**
	 * Set the filter mask to be the exact mask specified.
	 */
	private void setFilters(int maskToHide) {
		ViewerFilter[] filters = getFilters();
		if (filters != null) {
			for (int i = 0; i < filters.length; i++) {
				if (filters[i] instanceof CategoryFilter) {
					CategoryFilter filter = (CategoryFilter)filters[i];
					// Set the exact match to be applied on the filter
					filter.setMask(maskToHide);
					refresh();
					return;
				}
			}
		}
		// No category filter found -- add one
		addFilter(new CategoryFilter(maskToHide));
	}
	
	/**
	 * The sync mode has changed.  Update the filters.
	 */
	public void syncModeChanged(int mode) {
		this.syncMode = mode;
		updateFilters();
	}
	
	/**
	 * Sets the viewer filtering based on the current state
	 * of the filter actions.
	 */
	void updateFilters() {
		//do nothing if viewer is disposed
		Control control = getControl();
		if (control == null || control.isDisposed()) 
			return;
		
		//always show conflicts
		int filters = CategoryFilter.SHOW_CONFLICTS;
		
		//determine what other filters to apply based on current action states
		switch (syncMode) {
			case SyncView.SYNC_INCOMING:
			case SyncView.SYNC_MERGE:
				if (!showOnlyConflicts.isChecked()) {
					filters |= CategoryFilter.SHOW_INCOMING;
				}
				break;
			case SyncView.SYNC_OUTGOING:
				if (!showOnlyConflicts.isChecked()) {
					filters |= CategoryFilter.SHOW_OUTGOING;
				}
				break;
			case SyncView.SYNC_BOTH:
				boolean conflictsOnly = showOnlyConflicts.isChecked();
				//if showing only conflicts, don't allow these actions to happen
				showIncoming.setEnabled(!conflictsOnly);
				showOutgoing.setEnabled(!conflictsOnly);
				if (!conflictsOnly) {
					if (showIncoming.isChecked()) {
						filters |= CategoryFilter.SHOW_INCOMING;
					}
					if (showOutgoing.isChecked()) {
						filters |= CategoryFilter.SHOW_OUTGOING;
					}
				}
				break;
		}
		
		//determine whether to show pseudo conflicts
		if (!compareFileContents) {
			filters |= CategoryFilter.SHOW_PSEUDO_CONFLICTS;
		}
		setFilters(filters);
	}
}
