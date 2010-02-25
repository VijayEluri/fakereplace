package org.fakereplace.seam;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.Startup;
import org.jboss.seam.annotations.intercept.BypassInterceptors;
import org.jboss.seam.annotations.web.Filter;
import org.jboss.seam.web.AbstractFilter;

@Startup
@Scope(ScopeType.APPLICATION)
@Name("org.fakereplace.classRedefinitionFilter")
@BypassInterceptors
@Filter
public class ClassRedefinitionFilter extends AbstractFilter
{
    
    static class FileData
    {
	Long lastModified;
	File file;
	String className;
    }

    static final String[] fl = { "seam.properties", "META-INF/components.xml" };

    List<FileData> files = null;

    ReentrantLock lock = new ReentrantLock();

    Set<File> directories = new HashSet<File>();

    Method replaceMethod = null;

    String AGENT_CLASS = "org.fakereplace.Agent";

    boolean enabled = true;

    /**
     * gets a reference to the replaceClass method. If this fails
     * because the agent has not been installed then the filter is disabled
     * If the method suceed then the doInit method is called which scans 
     * the classpath for exploded jars that can have classes redefined
     */
    public ClassRedefinitionFilter()
    {
	try
	{
	    Class agent = Class.forName(AGENT_CLASS);
	    replaceMethod = agent.getMethod("redefine", ClassDefinition[].class);
	    doInit();

	} catch (Exception e)
	{
	    System.out.println("------------------------------------------------------------------------");
	    System.out.println("------ Fakereplace agent not availbile, hot deployment is disabled -----");
	    System.out.println("------------------------------------------------------------------------");
	    enabled = false;
	}
    }

    public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain arg2)
	    throws IOException, ServletException
    {
	if (enabled)
	{

	    if (lock.tryLock())
	    {
		try
		{
		    doReplace();
		} finally
		{
		    lock.unlock();
		}
	    }

	}
	arg2.doFilter(arg0, arg1);
    }

    void doReplace()
    {
	try
	{
	    List<ClassDefinition> classesToReplace = new ArrayList<ClassDefinition>();
	    for (FileData d : files)
	    {
		if (d.file.lastModified() > d.lastModified)
		{
		    System.out.println("File " + d.className + " has been modified, replacing");

		    Class ctr = Class.forName(d.className);
		    byte[] fileData = readFile(d.file);
		    ClassDefinition cd = new ClassDefinition(ctr, fileData);
		    classesToReplace.add(cd);
		    d.lastModified = d.file.lastModified();

		}
	    }
	    if (!classesToReplace.isEmpty())
	    {
		ClassDefinition[] data = new ClassDefinition[classesToReplace.size()];
		for (int i = 0; i < classesToReplace.size(); ++i)
		{
		    data[i] = classesToReplace.get(i);
		}
		replaceMethod.invoke(null, (Object) data);
	    }

	} catch (Exception e)
	{
	    e.printStackTrace();
	}
    }

    public void doInit()
    {

	try
	{
	    for (String resource : fl)
	    {
		Enumeration<URL> urls = getClass().getClassLoader().getResources(resource);

		while (urls.hasMoreElements())
		{
		    URL i = urls.nextElement();
		    String path = i.getPath();
		    path = path.substring(0, path.length() - resource.length() - 1);
		    File f = new File(path);
		    if (f.isDirectory() && path.endsWith(".jar"))
		    {
			directories.add(f);
		    }
		}

	    }
	    initialScan();
	} catch (IOException e)
	{
	    throw new RuntimeException(e);
	}

    }

    public void initialScan()
    {
	files = new ArrayList<FileData>();
	for (File f : directories)
	{
	    handleDirectory(f, f);
	}
    }

    private void handleDirectory(File dir, File rootDir)
    {
	if (!dir.isDirectory())
	    return;
	String dirPath = rootDir.getAbsolutePath();
	for (File f : dir.listFiles())
	{
	    if (!f.isDirectory())
	    {
		if (f.getName().endsWith(".class"))
		{
		    FileData fd = new FileData();
		    fd.file = f;
		    fd.lastModified = f.lastModified();
		    String d = f.getAbsolutePath().substring(dirPath.length() + 1);
		    d = d.replace('/', '.');
		    d = d.replace('\\', '.');
		    d = d.substring(0, d.length() - ".class".length());
		    System.out.println(d);
		    fd.className = d;
		    files.add(fd);
		}
	    } else
	    {
		handleDirectory(f, rootDir);
	    }
	}
    }

    byte[] readFile(File file) throws IOException
    {
	InputStream is = new FileInputStream(file);

	long length = file.length();

	byte[] bytes = new byte[(int) length];

	int offset = 0;
	int numRead = 0;
	while (offset < bytes.length
		&& (numRead = is.read(bytes, offset, bytes.length - offset)) >= 0)
	{
	    offset += numRead;
	}

	is.close();
	return bytes;
    }

}
