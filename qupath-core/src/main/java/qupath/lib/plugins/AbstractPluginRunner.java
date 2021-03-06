/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.plugins;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.SimpleThreadFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;


/**
 * Abstract PluginRunner to help with the creation of plugin runners for specific circumstances,
 * e.g. running through a GUI, or from a command line only.
 * 
 * Note!  This makes use of a static threadpool, which will be reused by all inheriting classes.
 * 
 * @author Pete Bankhead
 *
 * @param <T>
 */
public abstract class AbstractPluginRunner<T> implements PluginRunner<T> {
	
	final private static Logger logger = LoggerFactory.getLogger(AbstractPluginRunner.class);

	private static int numThreadsRequested = Runtime.getRuntime().availableProcessors();
	private static int counter = 0;

	private static ExecutorService pool;
	private ExecutorCompletionService<Runnable> service;

	private Map<Future<Runnable>, Runnable> pendingTasks = new HashMap<>();
	
	private boolean batchMode = false;
	private SimpleProgressMonitor monitor;
	
	private boolean tasksCancelled = false;
	
	protected AbstractPluginRunner(final boolean batchMode) {
		this.batchMode = batchMode;
	}

	/* (non-Javadoc)
	 * @see qupath.lib.plugins.PluginRunner#isBatchMode()
	 */
	@Override
	public boolean isBatchMode() {
		return batchMode;
	}
	
	/**
	 * Set the number of threads requested to be used for the next threadpool created.
	 * 
	 * The request is stored as-is, but may be adjusted if it is outside a valid range, i.e. &gt; 0 and &lt;= available processors.
	 * 
	 * @see #getNumThreadsRequested
	 * @see #getNumThreads
	 * 
	 * @param n
	 */
	public synchronized static void setNumThreadsRequested(int n) {
		if (numThreadsRequested == n)
			return;
		numThreadsRequested = n;
		// Need to shutdown the pool for this to take effect
		if (pool != null)
			pool.shutdown();
	}
	
	/**
	 * Get the number of threads requested.  This isn't necessarily the number that will be used for the next threadpool,
	 * since it may be &lt;= 0 or &gt; the available processors.
	 * 
	 * @see #setNumThreadsRequested
	 * @see #getNumThreads
	 * 
	 * @return
	 */
	public synchronized static int getNumThreadsRequested() {
		return numThreadsRequested;
	}
	
	/**
	 * Get the number of threads that will actually be used the next time a threadpool is constructed.
	 * 
	 * If getNumProcessorsRequested() returns a value between 1 and Runtime.getRuntime().availableProcessors() then this 
	 * is used.  Otherwise, Runtime.getRuntime().availableProcessors() is used.
	 * 
	 * This implementation may change (most likely to increase the upper limit, if it turns out to be too strict.)
	 * 
	 * @see #setNumThreadsRequested
	 * @see #getNumThreadsRequested
	 * 
	 * @return
	 */
	public synchronized static int getNumThreads() {
		int max = Runtime.getRuntime().availableProcessors();
		return numThreadsRequested <= 0 || numThreadsRequested > max ? max : numThreadsRequested;
	}

	protected abstract SimpleProgressMonitor makeProgressMonitor();
	
	/* (non-Javadoc)
	 * @see qupath.lib.plugins.PluginRunner#getImageData()
	 */
	@Override
	public abstract ImageData<T> getImageData();

	/* (non-Javadoc)
	 * @see qupath.lib.plugins.PluginRunner#getImageServer()
	 */
	@Override
	public ImageServer<T> getImageServer() {
		ImageData<T> imageData = getImageData();
		return imageData == null ? null : imageData.getServer();
	}
	
	/* (non-Javadoc)
	 * @see qupath.lib.plugins.PluginRunner#getHierarchy()
	 */
	@Override
	public PathObjectHierarchy getHierarchy() {
		ImageData<T> imageData = getImageData();
		return imageData == null ? null : imageData.getHierarchy();
	}
	
	/* (non-Javadoc)
	 * @see qupath.lib.plugins.PluginRunner#getSelectedObject()
	 */
	@Override
	public PathObject getSelectedObject() {
		PathObjectHierarchy hierarchy = getHierarchy();
		return hierarchy == null ? null : hierarchy.getSelectionModel().getSelectedObject();
	}

	
	/* (non-Javadoc)
	 * @see qupath.lib.plugins.PluginRunner#runTasks(java.util.Collection)
	 */
	@Override
	public synchronized void runTasks(Collection<Runnable> tasks) {
		
		if (tasks.isEmpty())
			return;
		
		// Reset cancelled status
		tasksCancelled = false;
		
		// Ensure we have a pool
		if (pool == null || pool.isShutdown()) {
			int n = getNumThreads();
			pool = Executors.newFixedThreadPool(n, new SimpleThreadFactory("plugin-runner-"+(+counter)+"-", false));
			logger.debug("New threadpool created with {} threads", n);
			service = new ExecutorCompletionService<>(pool);
		} else if (service == null)
			service = new ExecutorCompletionService<>(pool);
		
		monitor = makeProgressMonitor();
		monitor.startMonitoring(null, tasks.size(), true);
		for (Runnable task : tasks) {
			Future<Runnable> future = service.submit(task, task);
			pendingTasks.put(future, task);
		}
		// TODO: See if this needs to be shutdown here, or there's a better way..?
		// In any case, it was inhibiting application shutdown just letting it be...
		pool.shutdown();
		if (!pendingTasks.isEmpty())
			awaitCompletion();
	}
	
	
//	/* (non-Javadoc)
//	 * @see qupath.lib.plugins.PluginRunner#isRunning()
//	 */
//	@Override
//	public boolean isRunning() {
//		return !pendingTasks.isEmpty();
//	}

	
	/**
	 * Await the completion of currently-running tasks, notifying any listener if necessary.
	 */
	protected void awaitCompletion() {
		try {
			while (!pendingTasks.isEmpty()) {
				Future<Runnable> future = null;
				// Check if the monitor has been cancelled; if so, do any post processing if a task is available and otherwise cancel remaining ones
				if (!tasksCancelled && monitor != null && monitor.cancelled() && (future = service.poll()) == null) {
					// Cancel all enqueued tasks
					for (Future<?> entry : pendingTasks.keySet().toArray(new Future<?>[0])) {
						if (entry.cancel(true)) {
							pendingTasks.remove(entry);
							monitor.updateProgress(1, "", null);
						} else
							logger.debug("Cancel returned false for {}", entry);
					}
					tasksCancelled = true;
				}
				future = future == null ? service.take() : future;
//				logger.warn("Future: {}", future);
				// If the task finished without being cancelled, run post-processing if required & update the progress monitor
				if (!future.isCancelled()) {
					Runnable runnable = future.get();
					PathTask task = runnable instanceof PathTask ? (PathTask)runnable : null;
					if (task != null) {
						// Do post-processing if necessary - this includes updating the monitor
						postProcess(runnable);
					} else {
						updateMonitor(task);
					}
				}
				pendingTasks.remove(future);
			}
			if (monitor != null)
				monitor.pluginCompleted("Tasks completed!");
		} catch (InterruptedException e) {
			logger.error("Plugin interrupted: {}", e.getLocalizedMessage(), e);
			monitor.pluginCompleted("Completed with error " + e.getLocalizedMessage());
		} catch (ExecutionException e) {
			logger.error("Error running plugin: {}", e.getLocalizedMessage(), e);
//			Throwable e2 = e;
//			while ((e2 = e2.getCause()) != null) {
//				e2 = e2.fillInStackTrace();
//				logger.error("CAUSING Error running plugin: {}", e2.getLocalizedMessage(), e2);
//			}
//			e.printStackTrace();
			if (pool != null)
				pool.shutdownNow();
			monitor.pluginCompleted("Completed with error " + e.getLocalizedMessage());
		} catch (Exception e) {
			logger.error("Error running plugin: {}", e.getLocalizedMessage(), e);
			if (pool != null)
				pool.shutdownNow();
			monitor.pluginCompleted("Completed with error " + e.getLocalizedMessage());
		} finally {
			pendingTasks.clear();
		}
	}

	
	/**
	 * Perform post-processing after a task has complete.
	 * 
	 * This is necessary to call the taskComplete method (if the Runnable is an instance of PathTask),
	 * and also to update any progress monitor.
	 * 
	 * Note: Subclasses may choose to override this method so that it is called on a particular thread
	 * (e.g. with Platform.runLater() or SwingUtilities.invokeLater).
	 * 
	 * @param runnable
	 */
	protected void postProcess(final Runnable runnable) {
		PathTask task = runnable instanceof PathTask ? (PathTask)runnable : null;
		if (task != null) {
			task.taskComplete();
		}
//		runnable.getClass().getSimpleName()
//		System.err.println("Updating monitor from post processing");
		updateMonitor(task);
	}
	
	private void updateMonitor(final PathTask task) {
		if (monitor == null)
			return;
		String text = task == null ? "Completed" : task.getLastResultsDescription();
		monitor.updateProgress(1, text, null);
	}
	
	@Override
	public boolean isCancelled() {
		return tasksCancelled;
	}
	

}