/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.resolve;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.scope.NameHint;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.plugins.groovy.annotator.intentions.dynamic.DynamicManager;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrLabeledStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMember;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrGdkMethod;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ClassHint;
import static org.jetbrains.plugins.groovy.lang.resolve.processors.ClassHint.ResolveKind.*;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ClassResolverProcessor;
import org.jetbrains.plugins.groovy.lang.resolve.processors.PropertyResolverProcessor;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ResolverProcessor;

import java.util.HashSet;
import java.util.Set;

/**
 * @author ven
 */
public class ResolveUtil {
  public static boolean treeWalkUp(PsiElement place, PsiScopeProcessor processor) {
    PsiElement lastParent = null;
    PsiElement run = place;
    while (run != null) {
      if (!run.processDeclarations(processor, PsiSubstitutor.EMPTY, lastParent, place)) return false;
      lastParent = run;
      run = run.getContext();
    }

    return true;
  }

  public static boolean processChildren(PsiElement element, PsiScopeProcessor processor,
                                        PsiSubstitutor substitutor, PsiElement lastParent, PsiElement place) {
    PsiElement run = lastParent == null ? element.getLastChild() : lastParent.getPrevSibling();
    while (run != null) {
      if (!run.processDeclarations(processor, substitutor, null, place)) return false;
      run = run.getPrevSibling();
    }

    return true;
  }

  public static boolean processElement(PsiScopeProcessor processor, PsiNamedElement namedElement) {
    NameHint nameHint = processor.getHint(NameHint.class);
    String name = nameHint == null ? null : nameHint.getName();
    if (name == null || name.equals(namedElement.getName())) {
      return processor.execute(namedElement, PsiSubstitutor.EMPTY);
    }

    return true;
  }

  public static ClassHint.ResolveKind getResolveKind(PsiElement element) {
    if (element instanceof PsiVariable) return PROPERTY;
    if (element instanceof GrReferenceExpression) return PROPERTY;

    else if (element instanceof PsiMethod) return METHOD;

    else return CLASS_OR_PACKAGE;
  }

  public static boolean processNonCodeMethods(PsiType type, PsiScopeProcessor processor, Project project) {
    return processNonCodeMethods(type, processor, project, new HashSet<String>());
  }

  private static boolean processNonCodeMethods(PsiType type, PsiScopeProcessor processor, Project project, Set<String> visited) {
    String qName = rawCanonicalText(type);

    if (qName != null) {
      if (visited.contains(qName)) return true;
      visited.add(qName);
      for (PsiMethod defaultMethod : GroovyPsiManager.getInstance(project).getDefaultMethods(qName)) {
        if (!processElement(processor, defaultMethod)) return false;
      }

      for (PsiMethod method : DynamicManager.getInstance(project).getMethods(qName)) {
        if (!processElement(processor, method)) return false;
      }

      for (PsiVariable var : DynamicManager.getInstance(project).getProperties(qName)) {
        if (!processElement(processor, var)) return false;
      }


      if (type instanceof PsiArrayType) {
        //implicit super types
        PsiElementFactory factory = PsiManager.getInstance(project).getElementFactory();
        PsiClassType t = factory.createTypeByFQClassName("java.lang.Object", GlobalSearchScope.allScope(project));
        if (!processNonCodeMethods(t, processor, project, visited)) return false;
        t = factory.createTypeByFQClassName("java.lang.Comparable", GlobalSearchScope.allScope(project));
        if (!processNonCodeMethods(t, processor, project, visited)) return false;
        t = factory.createTypeByFQClassName("java.io.Serializable", GlobalSearchScope.allScope(project));
        if (!processNonCodeMethods(t, processor, project, visited)) return false;
      } else {
        for (PsiType superType : type.getSuperTypes()) {
          if (!processNonCodeMethods(TypeConversionUtil.erasure(superType), processor, project, visited)) return false;
        }
      }
    }

    return true;
  }

  private static String rawCanonicalText(PsiType type) {
    final String result = type.getCanonicalText();
    if (result == null) return null;
    final int i = result.indexOf('<');
    if (i > 0) return result.substring(0, i);
    return result;
  }

  public static PsiType getListTypeForSpreadOperator(GrReferenceExpression refExpr, PsiType componentType) {
    PsiClass clazz = findListClass(refExpr.getManager(), refExpr.getResolveScope());
    if (clazz != null) {
      PsiTypeParameter[] typeParameters = clazz.getTypeParameters();
      if (typeParameters.length == 1) {
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY.put(typeParameters[0], componentType);
        return refExpr.getManager().getElementFactory().createType(clazz, substitutor);
      }
    }

    return null;
  }

  public static PsiClass findListClass(PsiManager manager, GlobalSearchScope resolveScope) {
    return manager.findClass("java.util.List", resolveScope);
  }

  public static GroovyPsiElement resolveProperty(GroovyPsiElement place, String name) {
    PropertyResolverProcessor processor = new PropertyResolverProcessor(name, place, false);
    return resolveExistingElement(place, processor, GrVariable.class, GrReferenceExpression.class);
  }

  public static PsiClass resolveClass(GroovyPsiElement place, String name) {
    ClassResolverProcessor processor = new ClassResolverProcessor(name, place, false);
    return resolveExistingElement(place, processor, PsiClass.class);
  }

  private static <T> T resolveExistingElement(GroovyPsiElement place, ResolverProcessor processor, Class<? extends T>... classes) {
    treeWalkUp(place, processor);
    final GroovyResolveResult[] candidates = processor.getCandidates();
    for (GroovyResolveResult candidate : candidates) {
      final PsiElement element = candidate.getElement();
      if (element == place) continue;
      for (Class<? extends T> clazz : classes) {
        if (clazz.isInstance(element)) return (T) element;
      }
    }

    return null;
  }

  public static GrLabeledStatement resolveLabeledStatement(String label, PsiElement place) {
    while (place != null) {
      PsiElement run = place;
      while (run != null) {
        if (run instanceof GrLabeledStatement && label.equals(((GrLabeledStatement) run).getLabel()))
          return (GrLabeledStatement) run;

        run = run.getPrevSibling();
      }

      place = place.getContext();

      if (place instanceof GrMember || place instanceof GrClosableBlock) break;
    }
    return null;
  }

  public static boolean processCategoryMembers(PsiElement place, ResolverProcessor processor, PsiClassType thisType) {
    PsiElement prev = null;
    while (place != null) {
      if (place instanceof GrMember) break;

      if (place instanceof GrMethodCallExpression) {
        final GrMethodCallExpression call = (GrMethodCallExpression) place;
        final GrExpression invoked = call.getInvokedExpression();
        if (invoked instanceof GrReferenceExpression && "use".equals(((GrReferenceExpression) invoked).getReferenceName())) {
          final GrClosableBlock[] closures = call.getClosureArguments();
          if (closures.length == 1 && closures[0].equals(prev)) {
            if (useCategoryClass(call)) {
              final GrArgumentList argList = call.getArgumentList();
              if (argList != null) {
                final GrExpression[] args = argList.getExpressionArguments();
                if (args.length == 1 && args[0] instanceof GrReferenceExpression) {
                  final PsiElement resolved = ((GrReferenceExpression) args[0]).resolve();
                  if (resolved instanceof PsiClass) {
                    try {
                      processor.setCurrentFileResolveContext(call);
                      if (!resolved.processDeclarations(processor, PsiSubstitutor.EMPTY, null, place)) return false;
                    } finally {
                      processor.setCurrentFileResolveContext(null);
                    }
                  }
                }
              }
            }
          }
        }
      }

      prev = place;
      place = place.getContext();
    }

    return true;
  }

  private static boolean useCategoryClass(GrMethodCallExpression call) {

    final PsiMethod resolved = call.resolveMethod();
    if (resolved instanceof GrGdkMethod) {
      final PsiElementFactory factory = call.getManager().getElementFactory();
      final GlobalSearchScope scope = call.getResolveScope();
      final PsiType[] parametersType = {
          factory.createTypeByFQClassName("java.lang.Class", scope),
          factory.createTypeByFQClassName("groovy.lang.Closure", scope)
      };
      final MethodSignature pattern = MethodSignatureUtil.createMethodSignature("use", parametersType, PsiTypeParameter.EMPTY_ARRAY, PsiSubstitutor.EMPTY);
      return resolved.getSignature(PsiSubstitutor.EMPTY).equals(pattern);
    }

    return false;
  }

  public static PsiElement[] mapToElements(GroovyResolveResult[] candidates) {
    PsiElement[] elements = new PsiElement[candidates.length];
    for (int i = 0; i < elements.length; i++) {
      elements[i] = candidates[i].getElement();
    }

    return elements;
  }
}
