# storybook-addon-github

A Storybook addon that allows you to link a source code of your story on GitHub.

## Installation

```
npm i -D @kemuridama/storybook-addon-github
```

## Configuration

Add the following to your `.storybook/main.js`:

```
export default {
  addons: ['@kemuridama/storybook-addon-github'],
}
```

Then, add the following to your `.storybook/preview.js`:

```
export default {
  parameters: {
    github: {
      baseURL: "<GitHub Enterprise Server URL (optional)>",
      repository: "<repository name>",
      branch: "<branch name>",
    }
  }
}
```
