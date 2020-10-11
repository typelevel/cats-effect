// See https://docusaurus.io/docs/site-config for all the possible
// site configuration options.

const siteConfig = {
  title: 'Cats Effect', // Title for your website.
  tagline: 'The purely functional runtime for Scala',
  url: 'https://typelevel.org', // Your website URL
  baseUrl: '/cats-effect/',
  repoUrl: 'https://github.com/typelevel/cats-effect',
  projectName: 'cats-effect',
  githubHost: 'github.com',
  organizationName: 'typelevel',
  // For top-level user or org sites, the organization is still the same.
  // e.g., for the https://JoelMarcey.github.io site, it would be set like...
  //   organizationName: 'JoelMarcey'

  // For no header links in the top nav bar -> headerLinks: [],
  headerLinks: [
    {doc: 'installation', label: 'Docs'},
    {href: 'https://github.com/typelevel/cats-effect', label: "GitHub", external: true},
    {href: `/cats-effect/api/index.html`, label: 'API'},
    // {href: '/cats-effect/resources.html', label: 'Resources'}
  ],

  /* path to images for header/footer */
  headerIcon: 'img/cats-effect-logo.svg',
  footerIcon: 'img/cats-effect-logo.svg',
  favicon: 'img/favicon.png',

  /* Colors for website */
  colors: {
    primaryColor: '#d36d6f',
    secondaryColor: '#294066',
  },

  /* Custom fonts for website */
  /*
  fonts: {
    myFont: [
      "Times New Roman",
      "Serif"
    ],
    myOtherFont: [
      "-apple-system",
      "system-ui"
    ]
  },
  */

  // This copyright info is used in /core/Footer.js and blog RSS/Atom feeds.
  copyright: `Copyright (c) 2017-2020 Typelevel`,

  highlight: {
    // Highlight.js theme to use for syntax highlighting in code blocks.
    theme: 'github-gist',
  },

  // Add custom scripts here that would be placed in <script> tags.
  scripts: ['https://buttons.github.io/buttons.js'],

  // On page navigation for the current documentation page.
  onPageNav: 'separate',
  // No .html extensions for paths.
  cleanUrl: true,

  // Open Graph and Twitter card images.
  ogImage: 'img/undraw_online.svg',
  twitterImage: 'img/undraw_tweetstorm.svg',

  twitterUsername: 'typelevel',

  // For sites with a sizable amount of content, set collapsible to true.
  // Expand/collapse the links and subcategories under categories.
  // docsSideNavCollapsible: true,

  // Show documentation's last contributor's name.
  // enableUpdateBy: true,

  // Show documentation's last update time.
  // enableUpdateTime: true,
  docsSideNavCollapsible: true,

  users: [
    {
      image: 'img/adopters/dss.png',
      caption: 'Disney Streaming Services',
      title: 'Disney Streaming Services',
      infoLink: 'https://www.disneyplus.com',
      pinned: true
    },
    {
      image: 'img/adopters/philips.svg',
      caption: 'Philips',
      title: 'Philips',
      infoLink: 'https://www.philips.com',
      pinned: true
    },
    {
      image: 'img/adopters/itv.png',
      caption: 'ITV',
      title: 'ITV',
      infoLink: 'https://www.itv.com',
      pinned: true
    },
    {
      image: 'img/adopters/inner-product.png',
      caption: 'Inner Product',
      title: 'Inner Product',
      infoLink: 'https://www.inner-product.com/',
      pinned: true
    },
    {
      image: 'img/adopters/stripe.svg',
      caption: 'Stripe',
      title: 'Stripe',
      infoLink: 'https://stripe.com/',
      pinned: true
    },
    {
      image: 'img/adopters/comcast.png',
      caption: 'Comcast',
      title: 'Comcast',
      infoLink: 'https://comcast.com/',
      pinned: true
    }
  ]
};

module.exports = siteConfig;
