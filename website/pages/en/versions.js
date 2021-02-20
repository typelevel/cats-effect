const React = require('react');

const CompLibrary = require('../../core/CompLibrary');

const Container = CompLibrary.Container;

const CWD = process.cwd();

const versions = require(`${CWD}/versions.json`);

function Versions(props) {
  const {config: siteConfig} = props;
  const repoUrl = `https://github.com/${siteConfig.organizationName}/${siteConfig.projectName}`;
  const baseUrl = siteConfig.baseUrl + siteConfig.docsUrl + '/' + (props.language ? props.language + '/' : '');
  return (
    <div className="docMainWrapper wrapper">
      <Container className="mainContainer versionsContainer">
        <div className="post">
          <header className="postHeader">
            <h1>{siteConfig.title} Versions</h1>
          </header>

          <p>
            Cats Effect maintains two independent release series for the 2.x series and the 3.x series.
            Documentation and scaladocs for the stable and pre-release version on each series are provided.
            The 2.x series will be considered current until 3.x reaches stable.
          </p>

          <h3 id="latest">Versions</h3>
          <table className="versions">
            <tbody>
              <tr>
                <th>2.x</th>
                <td>
                  <a href={baseUrl + '2.x/getting-started'}>Documentation</a>
                </td>
                <td>
                  <a href={'/cats-effect/api/2.x'}>Scaladoc</a>
                </td>
              </tr>
              <tr>
                <th>3.x</th>
                <td>
                  <a href={baseUrl + 'getting-started'}>Documentation</a>
                </td>
                <td>
                  <a href={'/cats-effect/api/3.x'}>Scaladoc</a>
                </td>
              </tr>
            </tbody>
          </table>

        </div>
      </Container>
    </div>
  );
}

module.exports = Versions;
