/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.PrebuiltJar;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

/**
 * Description for a {@link BuildRule} that wraps an {@code .aar} file as an Android dependency.
 * <p>
 * This represents an Android Library Project packaged as an {@code .aar} bundle as specified by:
 * http://tools.android.com/tech-docs/new-build-system/aar-format. When it is in the packageable
 * deps of an {@link AndroidBinary}, its contents will be included in the generated APK.
 * <p>
 * Note that the {@code aar} may be specified as a {@link SourcePath}, so it could be either
 * a binary {@code .aar} file checked into version control, or a zip file that conforms to the
 * {@code .aar} specification that is generated by another build rule.
 */
public class AndroidPrebuiltAarDescription
    implements Description<AndroidPrebuiltAarDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_prebuilt_aar");

  private static final Flavor AAR_PREBUILT_JAR_FLAVOR = ImmutableFlavor.of("aar_prebuilt_jar");
  private static final Flavor AAR_UNZIP_FLAVOR = ImmutableFlavor.of("aar_unzip");

  private final JavacOptions javacOptions;

  public AndroidPrebuiltAarDescription(JavacOptions javacOptions) {
    this.javacOptions = javacOptions;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);
    UnzipAar unzipAar = createUnzipAar(params, args.aar, buildRuleResolver);

    Iterable<PrebuiltJar> javaDeps = Iterables.concat(
        Iterables.filter(
            buildRuleResolver.getAllRules(args.deps.get()),
            PrebuiltJar.class),
        Iterables.transform(
            Iterables.filter(
                buildRuleResolver.getAllRules(args.deps.get()),
                AndroidPrebuiltAar.class),
            new Function<AndroidPrebuiltAar, PrebuiltJar>() {
              @Override
              public PrebuiltJar apply(AndroidPrebuiltAar input) {
                return input.getPrebuiltJar();
              }
            }));

    PrebuiltJar prebuiltJar = buildRuleResolver.addToIndex(
        createPrebuiltJar(
            unzipAar,
            params,
            pathResolver,
            ImmutableSortedSet.<BuildRule>copyOf(javaDeps)));
    AndroidResource androidResource = buildRuleResolver.addToIndex(
        createAndroidResource(unzipAar, params, pathResolver));
    return buildRuleResolver.addToIndex(new AndroidPrebuiltAar(
        /* androidLibraryParams */ params.copyWithDeps(
            /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(
                    androidResource,
                    prebuiltJar)),
            /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(unzipAar))),
        /* resolver */ pathResolver,
        /* proguardConfig */ new BuildTargetSourcePath(
            unzipAar.getBuildTarget(),
            unzipAar.getProguardConfig()),
        /* nativeLibsDirectory */ new BuildTargetSourcePath(
            unzipAar.getBuildTarget(),
            unzipAar.getNativeLibsDirectory()),
        /* prebuiltJar */ prebuiltJar,
        /* androidResource */ androidResource,
        /* unzipRule */ unzipAar,
        /* javacOptions */ javacOptions,
        /* exportedDeps */ javaDeps));
  }

  /**
   * Creates a build rule to unzip the prebuilt AAR and get the components needed for the
   * AndroidPrebuiltAar, PrebuiltJar, and AndroidResource
   */
  private UnzipAar createUnzipAar(
      BuildRuleParams originalBuildRuleParams,
      SourcePath aarFile,
      BuildRuleResolver ruleResolver) {
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    UnflavoredBuildTarget originalBuildTarget =
        originalBuildRuleParams.getBuildTarget().checkUnflavored();

    BuildRuleParams unzipAarParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_UNZIP_FLAVOR),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.copyOf(
            resolver.filterBuildRuleInputs(aarFile))));
    UnzipAar unzipAar = new UnzipAar(unzipAarParams, resolver, aarFile);
    return ruleResolver.addToIndex(unzipAar);
  }


  private PrebuiltJar createPrebuiltJar(
      UnzipAar unzipAar,
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps) {
    BuildRuleParams buildRuleParams = params.copyWithChanges(
        /* buildTarget */ BuildTargets.createFlavoredBuildTarget(
            params.getBuildTarget().checkUnflavored(),
            AAR_PREBUILT_JAR_FLAVOR),
        /* declaredDeps */ Suppliers.ofInstance(deps),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(unzipAar)));
    return new PrebuiltJar(
        /* params */ buildRuleParams,
        /* resolver */ resolver,
        /* binaryJar */ new BuildTargetSourcePath(
            unzipAar.getBuildTarget(),
            unzipAar.getPathToClassesJar()),
        /* sourceJar */ Optional.<SourcePath>absent(),
        /* gwtJar */ Optional.<SourcePath>absent(),
        /* javadocUrl */ Optional.<String>absent(),
        /* mavenCoords */ Optional.<String>absent());

  }

  private AndroidResource createAndroidResource(
      UnzipAar unzipAar,
      BuildRuleParams params,
      SourcePathResolver resolver) {
    BuildRuleParams buildRuleParams = params.copyWithChanges(
        /* buildTarget */ BuildTargets.createFlavoredBuildTarget(
            params.getBuildTarget().checkUnflavored(),
            ImmutableFlavor.of("aar_android_resource")),
        /* declaredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(unzipAar)));

    return new AndroidResource(
        /* buildRuleParams */ buildRuleParams,
        /* resolver */ resolver,
        /* deps */ ImmutableSortedSet.<BuildRule>of(),
        /* res */ new BuildTargetSourcePath(
            unzipAar.getBuildTarget(),
            unzipAar.getResDirectory()),
        /* resSrcs */ ImmutableSortedSet.<Path>of(),
        /* rDotJavaPackage */ null,
        /* assets */ new BuildTargetSourcePath(
            unzipAar.getBuildTarget(),
            unzipAar.getAssetsDirectory()),
        /* assetsSrcs */ ImmutableSortedSet.<Path>of(),
        /* manifestFile */ new BuildTargetSourcePath(
            unzipAar.getBuildTarget(),
            unzipAar.getAndroidManifest()),
        /* hasWhitelistedStrings */ false);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public SourcePath aar;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
