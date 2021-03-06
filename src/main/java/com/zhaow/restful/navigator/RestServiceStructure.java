package com.zhaow.restful.navigator;

import com.intellij.icons.AllIcons;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.tree.AsyncTreeModel;
import com.intellij.ui.tree.StructureTreeModel;
import com.intellij.ui.treeStructure.*;
import com.intellij.util.OpenSourceUtil;
import com.zhaow.restful.common.KtFunctionHelper;
import com.zhaow.restful.common.PsiMethodHelper;
import com.zhaow.restful.common.ToolkitIcons;
import com.zhaow.restful.method.HttpMethod;
import com.zhaow.restful.navigation.action.RestServiceItem;
import gnu.trove.THashMap;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.idea.KotlinLanguage;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RestServiceStructure extends SimpleTreeStructure {
    public static final Logger LOG = Logger.getInstance(RestServiceStructure.class);

    private SimpleTreeBuilder myTreeBuilder;
    private AsyncTreeModel asyncTreeModel;
    private StructureTreeModel structureTreeModel;

    private SimpleTree myTree;

    private final Project myProject;
    private RootNode myRoot = new RootNode();
    private int serviceCount = 0;
    private AnActionEvent anActionEvent;

    private final RestServiceProjectsManager myProjectsManager;

    RestServiceDetail myRestServiceDetail;

    private final Map<RestServiceProject, ProjectNode> myProjectToNodeMapping = new THashMap<>();

    public RestServiceStructure(Project project,
                                RestServiceProjectsManager projectsManager,
                                SimpleTree tree) {
        myProject = project;
        myProjectsManager = projectsManager;
        myTree = tree;
        myRestServiceDetail = project.getComponent(RestServiceDetail.class);

        configureTree(tree);

        myTreeBuilder = new SimpleTreeBuilder(tree, (DefaultTreeModel) tree.getModel(), this, null);
        Disposer.register(myProject, myTreeBuilder);

        //myTreeBuilder.initRoot();
        //myTreeBuilder.expand(myRoot, null);

        // structureTreeModel = new StructureTreeModel(this, Disposable ?);
        //asyncTreeModel = new AsyncTreeModel()

    }

    private void configureTree(SimpleTree tree) {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
    }

    @Override
    public RootNode getRootElement() {
        return myRoot;
    }

    public void update(AnActionEvent event) {
        RestServiceProjectsManager restServiceProjectsManager = RestServiceProjectsManager.getInstance(myProject);
        if (structureTreeModel == null) {
            structureTreeModel = new StructureTreeModel(this, restServiceProjectsManager);

        }

        List<RestServiceProject> projects = restServiceProjectsManager.getServiceProjects(event);
        updateProjects(projects);
    }

    public void updateProjects(List<RestServiceProject> projects) {
        serviceCount = 0;

        for (RestServiceProject each : projects) {
            serviceCount += each.serviceItems.size();
            ProjectNode node = findNodeFor(each);
            if (node == null) {
                node = new ProjectNode(myRoot, each);
                myProjectToNodeMapping.put(each, node);
            }
        }
        myRoot.updateProjectNodes(projects);
    }

    private ProjectNode findNodeFor(RestServiceProject project) {
        return myProjectToNodeMapping.get(project);
    }

    public void updateFrom(SimpleNode node) {
        if (node != null) {
            //structureTreeModel.expand(node, myTree, );
            myTreeBuilder.addSubtreeToUpdateByElement(node);
        }
    }

    private void updateUpTo(SimpleNode node) {
        SimpleNode each = node;
        while (each != null) {
            updateFrom(each);
            each = each.getParent();
        }
    }

    public static <T extends BaseSimpleNode> List<T> getSelectedNodes(SimpleTree tree, Class<T> nodeClass) {
        final List<T> filtered = new ArrayList<>();
        for (SimpleNode node : getSelectedNodes(tree)) {
            if ((nodeClass != null) && (!nodeClass.isInstance(node))) {
                filtered.clear();
                break;
            }
            filtered.add((T) node);
        }
        return filtered;
    }

    private static List<SimpleNode> getSelectedNodes(SimpleTree tree) {
        List<SimpleNode> nodes = new ArrayList<>();
        TreePath[] treePaths = tree.getSelectionPaths();
        if (treePaths != null) {
            for (TreePath treePath : treePaths) {
                nodes.add(tree.getNodeFor(treePath));
            }
        }
        return nodes;
    }


    public abstract class BaseSimpleNode extends CachingSimpleNode {

        protected BaseSimpleNode(SimpleNode aParent) {
            super(aParent);
        }

        @Nullable
        @NonNls
        String getActionId() {
            return null;
        }

        @Nullable
        @NonNls
        String getMenuId() {
            return null;
        }

        @Override
        public void cleanUpCache() {
            super.cleanUpCache();
        }

        protected void childrenChanged() {
            BaseSimpleNode each = this;
            while (each != null) {
                each.cleanUpCache();
                each = (BaseSimpleNode) each.getParent();
            }
            updateUpTo(this);
        }

    }


    public class RootNode extends BaseSimpleNode {
        List<ProjectNode> projectNodes = new ArrayList<>();

        protected RootNode() {
            super(null);
            getTemplatePresentation().setIcon(AllIcons.Nodes.Module);
            setIcon(AllIcons.Nodes.Module); //兼容 IDEA 2016
        }

        @Override
        protected SimpleNode[] buildChildren() {
            return projectNodes.toArray(new SimpleNode[projectNodes.size()]);
        }

        @Override
        public String getName() {
            String s = "Found %d services ";// in {controllerCount} Controllers";
            return serviceCount > 0 ? String.format(s, serviceCount) : null;
        }

        @Override
        public void handleSelection(SimpleTree tree) {
            resetRestServiceDetail();

        }

        public void updateProjectNodes(List<RestServiceProject> projects) {
            projectNodes.clear();
            for (RestServiceProject project : projects) {
                ProjectNode projectNode = new ProjectNode(this, project);
                projectNodes.add(projectNode);
            }

            updateFrom(getParent());
            childrenChanged();
        }

    }

    public class ProjectNode extends BaseSimpleNode {
        List<ServiceNode> serviceNodes = new ArrayList<>();
        RestServiceProject myProject;


        public ProjectNode(SimpleNode parent, RestServiceProject project) {
            super(parent);
            myProject = project;

            getTemplatePresentation().setIcon(ToolkitIcons.MODULE);
            setIcon(ToolkitIcons.MODULE); //兼容 IDEA 2016

            updateServiceNodes(project.serviceItems);
        }

        private void updateServiceNodes(List<RestServiceItem> serviceItems) {
            serviceNodes.clear();
            for (RestServiceItem serviceItem : serviceItems) {
                serviceNodes.add(new ServiceNode(this, serviceItem));
            }

            SimpleNode parent = getParent();
            if (parent != null) {
                ((BaseSimpleNode) parent).cleanUpCache();
            }
            updateFrom(parent);
        }

        @Override
        protected SimpleNode[] buildChildren() {
            return serviceNodes.toArray(new SimpleNode[serviceNodes.size()]);
        }

        @Override
        public String getName() {
            return myProject.getModuleName();
        }


        @Override
        @Nullable
        @NonNls
        protected String getActionId() {
            return "Toolkit.RefreshServices";
        }

        @Override
        public void handleSelection(SimpleTree tree) {
            resetRestServiceDetail();
        }

        @Override
        public void handleDoubleClickOrEnter(SimpleTree tree, InputEvent inputEvent) {
        }
    }

    public class ServiceNode extends BaseSimpleNode {
        RestServiceItem myServiceItem;

        public ServiceNode(SimpleNode parent, RestServiceItem serviceItem) {
            super(parent);
            myServiceItem = serviceItem;

            Icon icon = ToolkitIcons.METHOD.get(serviceItem.getMethod());
            if (icon != null) {
                getTemplatePresentation().setIcon(icon);
                setIcon(icon); //兼容 IDEA 2016
            }
        }

        @Override
        protected SimpleNode[] buildChildren() {
            return new SimpleNode[0];
        }

        @Override
        public String getName() {
            String name = myServiceItem.getName();
            return name;
        }

        @Override
        public void handleSelection(SimpleTree tree) {
            ServiceNode selectedNode = (ServiceNode) tree.getSelectedNode();
            showServiceDetail(selectedNode.myServiceItem);
        }

        /**
         * 显示服务详情，url
         */
        private void showServiceDetail(RestServiceItem serviceItem) {

            myRestServiceDetail.resetRequestTabbedPane();

            String method = serviceItem.getMethod() != null ? String.valueOf(serviceItem.getMethod()) : HttpMethod.GET.name();
            myRestServiceDetail.setMethodValue(method);
            myRestServiceDetail.setUrlValue(serviceItem.getFullUrl());

            String requestParams = "";
            String requestBodyJson = "";
            PsiElement psiElement = serviceItem.getPsiElement();
            if (psiElement.getLanguage() == JavaLanguage.INSTANCE) {
                PsiMethodHelper psiMethodHelper = PsiMethodHelper.create(serviceItem.getPsiMethod()).withModule(serviceItem.getModule());
                requestParams = psiMethodHelper.buildParamString();
                requestBodyJson = psiMethodHelper.buildRequestBodyJson();

            } else if (psiElement.getLanguage() == KotlinLanguage.INSTANCE) {
                if (psiElement instanceof KtNamedFunction) {
                    KtNamedFunction ktNamedFunction = (KtNamedFunction) psiElement;
                    KtFunctionHelper ktFunctionHelper = KtFunctionHelper.create(ktNamedFunction).withModule(serviceItem.getModule());
                    requestParams = ktFunctionHelper.buildParamString();
                    requestBodyJson = ktFunctionHelper.buildRequestBodyJson();
                }

            }

            myRestServiceDetail.addRequestParamsTab(requestParams);


            if (StringUtils.isNotBlank(requestBodyJson)) {
                myRestServiceDetail.addRequestBodyTabPanel(requestBodyJson);
            }
        }

        @Override
        public void handleDoubleClickOrEnter(SimpleTree tree, InputEvent inputEvent) {
            ServiceNode selectedNode = (ServiceNode) tree.getSelectedNode();

            RestServiceItem myServiceItem = selectedNode.myServiceItem;
            PsiElement psiElement = myServiceItem.getPsiElement();

            if (!psiElement.isValid()) {
                // PsiDocumentManager.getInstance(psiMethod.getProject()).commitAllDocuments();
                // try refresh service
                LOG.info("psiMethod is invalid: ");
                LOG.info(psiElement.toString());
                RestServicesNavigator.getInstance(myServiceItem.getModule().getProject()).scheduleStructureUpdate();
            }

            if (psiElement.getLanguage() == JavaLanguage.INSTANCE) {
                PsiMethod psiMethod = myServiceItem.getPsiMethod();
                OpenSourceUtil.navigate(psiMethod);

            } else if (psiElement.getLanguage() == KotlinLanguage.INSTANCE) {
                if (psiElement instanceof KtNamedFunction) {
                    KtNamedFunction ktNamedFunction = (KtNamedFunction) psiElement;
                    OpenSourceUtil.navigate(ktNamedFunction);
                }
            }
        }

        @Override
        @Nullable
        @NonNls
        protected String getMenuId() {
            return "Toolkit.NavigatorServiceMenu";
        }

    }

    private void resetRestServiceDetail() {
        myRestServiceDetail.resetRequestTabbedPane();
        myRestServiceDetail.setMethodValue(HttpMethod.GET.name());
        myRestServiceDetail.setUrlValue("URL");

        myRestServiceDetail.initTab();
    }

}
