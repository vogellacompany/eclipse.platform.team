package org.eclipse.team.internal.ccvs.core.resources;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.team.ccvs.core.CVSTag;
import org.eclipse.team.ccvs.core.ICVSRemoteFile;
import org.eclipse.team.ccvs.core.ICVSRemoteResource;
import org.eclipse.team.ccvs.core.ICVSRepositoryLocation;
import org.eclipse.team.ccvs.core.ILogEntry;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.sync.IRemoteResource;
import org.eclipse.team.internal.ccvs.core.CVSException;
import org.eclipse.team.internal.ccvs.core.Client;
import org.eclipse.team.internal.ccvs.core.Policy;
import org.eclipse.team.internal.ccvs.core.connection.CVSRepositoryLocation;
import org.eclipse.team.internal.ccvs.core.response.IResponseHandler;
import org.eclipse.team.internal.ccvs.core.response.custom.LogHandler;

/**
 * This class provides the implementation of ICVSRemoteFile and IManagedFile for
 * use by the repository and sync view.
 */
public class RemoteFile extends RemoteResource implements ICVSRemoteFile, ICVSFile  {

	// cache for file contents received from the server
	private byte[] contents;
	
	/**
	 * Static method which creates a file as a single child of its parent.
	 * This should only be used when one is only interested in the file alone.
	 */
	public static RemoteFile createFile(RemoteFolder parent, ICVSFile managed) throws CVSException {
		ResourceSyncInfo info = managed.getSyncInfo();
		RemoteFile file = new RemoteFile(parent, managed.getName(), info.getRevision(), info.getTag());
		parent.setChildren(new ICVSRemoteResource[] {file});
		return file;
	}
	/**
	 * Constructor for RemoteFile.
	 */
	public RemoteFile(RemoteFolder parent, String name, CVSTag tag) {
		this(parent, name, "0", tag);
	}
	
	public RemoteFile(RemoteFolder parent, String name, String revision, CVSTag tag) {
		// XXX the keyword type of this remote file must be set correctly or else the 
		// getContents may mangle the file.
		this.parent = parent;
		info = new ResourceSyncInfo(name, revision, "dummy", "-kb", tag, "u=rw,g=rw,o=rw");
	}

	/**
	 * @see ICVSResource#accept(IManagedVisitor)
	 */
	public void accept(ICVSResourceVisitor visitor) throws CVSException {
		visitor.visitFile(this);
	}

	/**
	 * @see ICVSRemoteFile#getContents()
	 */
	public InputStream getContents(final IProgressMonitor monitor) {
		
		try {
			if(contents==null) {
				List localOptions = getLocalOptionsForTag();
				Client.execute(
					Client.UPDATE,
					Client.EMPTY_ARGS_LIST, 
					new String[]{"-r", info.getRevision(), "-C"},
					new String[]{getName()}, 
					parent,
					monitor,
					getPrintStream(),
					(CVSRepositoryLocation)getRepository(),
					null);
			}
			return new ByteArrayInputStream(contents);
		} catch(CVSException e) {
			return null;
		}
	}

	/**
	 * @see ICVSRemoteFile#getLogEntries()
	 */
	public ILogEntry[] getLogEntries(IProgressMonitor monitor) throws CVSException {
		
		// Perform a "cvs log..." with a custom message handler
		final List entries = new ArrayList();
		Client.execute(
			Client.LOG,
			Client.EMPTY_ARGS_LIST, 
			Client.EMPTY_ARGS_LIST,
			new String[]{getName()}, 
			parent,
			monitor,
			getPrintStream(),
			(CVSRepositoryLocation)getRepository(),
			new IResponseHandler[] {new LogHandler(this, entries)});
		return (ILogEntry[])entries.toArray(new ILogEntry[entries.size()]);
	}
	
	/**
	 * @see ICVSRemoteFile#getRevision()
	 */
	public String getRevision() {
		return info.getRevision();
	}
	
	public RemoteFile toRevision(String revision) {
		return new RemoteFile(parent, getName(), revision, CVSTag.DEFAULT);
	}
	
		/**
	 * @see IManagedFile#getSize()
	 */
	public long getSize() {
		return 0;
	}

	/**
	 * @see IManagedFile#getFileInfo()
	 */
	public ResourceSyncInfo getSyncInfo() {
		return info;
	}

	public ICVSFolder getParent() {
		return parent;
 	}
 	
	/**
	 * @see ICVSResource#getRelativePath(ICVSFolder)
	 */
	public String getRelativePath(ICVSFolder ancestor) throws CVSException {
		String result = parent.getRelativePath(ancestor);
		if (result.length() == 0)
			return getName();
		else
			return result + Client.SERVER_SEPARATOR + getName();
	}
	
	/**
	 * @see ICVSResource#getRemoteLocation(ICVSFolder)
	 */
	public String getRemoteLocation(ICVSFolder stopSearching) throws CVSException {
		return parent.getRemoteLocation(stopSearching) + Client.SERVER_SEPARATOR + getName();
	}
	
	/**
	 * Get the remote path for the receiver relative to the repository location path
	 */
	public String getRemotePath() {
		String parentPath = parent.getRemotePath();
		return parentPath + Client.SERVER_SEPARATOR + getName();
	}
	
	/**
	 * Return the server root directory for the repository
	 */
	public ICVSRepositoryLocation getRepository() {
		return parent.getRepository();
	}
	
	/**
	 * @see IManagedFile#setFileInfo(FileProperties)
	 */
	public void setSyncInfo(ResourceSyncInfo fileInfo) {
		info = fileInfo;
	}

	public void setRevision(String revision) {
		new ResourceSyncInfo(info.getName(), revision, info.getTimeStamp(), info.getKeywordMode(), info.getTag(), info.getPermissions());
	}
		
	/**
	 * @see IManagedFile#sendTo(OutputStream, IProgressMonitor, boolean)
	 */
	public void sendTo(
		OutputStream out,
		IProgressMonitor monitor,
		boolean binary)
		throws CVSException {
			try {
				String SERVER_NEWLINE = "\n";
				// Send the size to the server and no contents
				out.write(0);
				out.write(SERVER_NEWLINE.getBytes());				
			} catch(IOException e) {
			}				
	}

	/**
	 * @see IManagedFile#receiveFrom(InputStream, IProgressMonitor, long, boolean)
	 */
	public void receiveFrom(
		InputStream inputStream,
		IProgressMonitor monitor,
		long size,
		boolean binary,
		boolean readOnly)
		throws CVSException {
			
		// NOTE: This should be changed such that the client or connection handles
		// the proper transfer
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			if (binary)
				LocalFile.transferWithProgress(inputStream, bos, (long)size, monitor, "");
			else
				LocalFile.transferText(inputStream, bos, (long)size, monitor, "", false);
			contents = bos.toByteArray();
		} catch (IOException ex) {
			throw CVSException.wrapException(ex);
		}
	}

	/**
	 * @see IManagedFile#getTimeStamp()
	 */
	public String getTimeStamp() throws CVSFileNotFoundException {
		return null;
	}

	/**
	 * @see IManagedFile#setTimeStamp(String)
	 */
	public void setTimeStamp(String date) throws CVSException {
	}

	/**
	 * @see IManagedFile#isDirty()
	 * 
	 * The file is direty if its contents haven't been fetched yet
	 */
	public boolean isDirty() throws CVSException {
		return contents == null;
	}

	/**
	 * @see IManagedFile#moveTo(IManagedFile)
	 */
	public void moveTo(String mFile) throws CVSException, ClassCastException {		
		// Do nothing
	}
	
	/*
	 * @see IRemoteResource#members(IProgressMonitor)
	 */
	public IRemoteResource[] members(IProgressMonitor progress) throws TeamException {
		return new IRemoteResource[0];
	}

	/*
	 * @see IRemoteResource#isContainer()
	 */
	public boolean isContainer() {
		return false;
	}

	/*
	 * @see ICVSResource#isFolder()
	 */
	public boolean isFolder() {
		return false;
	}
	
	public boolean updateRevision(CVSTag tag, IProgressMonitor monitor) throws CVSException {
		return parent.updateRevision(this, tag, monitor);
	}
	
	/*
	 * Get the local options for including a tag in a CVS command
	 */
	protected List getLocalOptionsForTag() {
		List localOptions = new ArrayList();
		CVSTag tag = info.getTag();
		if ((tag != null) && (tag.getType() != tag.HEAD)) { 
			localOptions.add(Client.TAG_OPTION);
			localOptions.add(tag.getName()); 
		}
		return localOptions;
	}
}

