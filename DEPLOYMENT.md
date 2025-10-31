# Deployment Guide for DSCloj

This guide explains how to deploy DSCloj to Clojars using deps-deploy.

## Prerequisites

1. **Clojars Account**: You need a Clojars account
2. **Clojars Credentials**: You'll need your Clojars username and deploy token

### Getting Your Clojars Deploy Token

1. Go to https://clojars.org
2. Log in to your account
3. Click on your username in the top right
4. Go to "Deploy Tokens"
5. Create a new deploy token or use an existing one

## Setting Environment Variables

Before deploying, set the following environment variables:

```bash
export CLOJARS_USERNAME="your-clojars-username"
export CLOJARS_PASSWORD="your-deploy-token"
```

Or create a `.env` file (make sure it's in `.gitignore`):

```bash
# .env
CLOJARS_USERNAME=your-clojars-username
CLOJARS_PASSWORD=your-deploy-token
```

## Deployment Steps

### 1. Update Version

Before deploying, update the version in `deps.edn`. Add a `:version` key to the root map:

```clojure
{:paths ["src" "resources"]
 :version "0.1.0"  ; Update this for each release
 :deps {...}
 ...}
```

Alternatively, you can set the version as an environment variable before deployment:

```bash
export PROJECT_VERSION="0.1.0"
```

### 2. Run Tests

Ensure all tests pass:

```bash
clojure -M:test -m kaocha.runner
```

### 3. Build and Deploy

Deploy to Clojars using the Makefile:

```bash
make deploy
```

This will:
- Check that environment variables are set
- Run all tests
- Auto-generate pom.xml from deps.edn metadata (handled by deps-deploy)
- Deploy to Clojars
- Show the verification URL

Or deploy manually:

```bash
clojure -X:deploy
```

**Note:** You don't need to manually create or maintain pom.xml - deps-deploy automatically generates it from the `:pom-data` in deps.edn during deployment.

### 4. Verify Deployment

After deployment, verify the release at:
- https://clojars.org/tech.unravel/DSCloj

## Version Naming Convention

- **Alpha releases**: `0.1.0-alpha`, `0.2.0-alpha`
- **Beta releases**: `0.1.0-beta`, `0.2.0-beta`
- **Release candidates**: `0.1.0-RC1`, `0.1.0-RC2`
- **Stable releases**: `0.1.0`, `0.2.0`, `1.0.0`

## Troubleshooting

### Authentication Failed

If you get authentication errors:
1. Verify your Clojars credentials are correct
2. Make sure you're using a deploy token, not your password
3. Check that environment variables are set correctly

### POM Validation Errors

If you get POM validation errors:
1. Ensure all required fields in `:pom-data` in `deps.edn` are filled
2. Check that the version number is valid
3. Verify SCM URLs in `:pom-data` are correct

### Artifact Already Exists

You cannot overwrite an existing version. You must:
1. Increment the version number in `pom.xml`
2. Deploy again

## Quick Deployment

The easiest way to deploy is using the Makefile:

```bash
# 1. Set your credentials
export CLOJARS_USERNAME="your-username"
export CLOJARS_PASSWORD="your-deploy-token"

# 2. (Optional) Set version if not in deps.edn
export PROJECT_VERSION="0.1.0"

# 3. Run make deploy
make deploy
```

## Release Checklist

- [ ] All tests pass (`make test`)
- [ ] Version updated in `deps.edn` or set as environment variable
- [ ] CHANGELOG updated (if you have one)
- [ ] README updated with new version
- [ ] Environment variables set (CLOJARS_USERNAME and CLOJARS_PASSWORD)
- [ ] Deploy to Clojars (`make deploy`)
- [ ] Verify deployment on Clojars
- [ ] Tag the release in Git: `git tag v0.1.0`
- [ ] Push tags: `git push origin --tags`

## Understanding pom.xml

You don't need to create or maintain a `pom.xml` file manually. The deployment process works as follows:

1. All project metadata is defined in `deps.edn` under `:pom-data`
2. When you run `make deploy`, deps-deploy automatically generates `pom.xml` from this metadata
3. The generated `pom.xml` is temporary and not tracked in git (it's in `.gitignore`)
4. For version management, you can either:
   - Add `:version "x.y.z"` to `deps.edn`
   - Set the `PROJECT_VERSION` environment variable before deploying

## Additional Resources

- [deps-deploy documentation](https://github.com/slipset/deps-deploy)
- [Clojars documentation](https://github.com/clojars/clojars-web/wiki/Pushing)
- [Semantic Versioning](https://semver.org/)
