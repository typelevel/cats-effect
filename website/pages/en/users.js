const React = require('react');

const CompLibrary = require('../../core/CompLibrary');

const Container = CompLibrary.Container;

const users = [
  ['Azavea', 'https://www.azavea.com/'],
  ['Anduin Transactions', 'https://www.anduintransact.com/'],
  ['Banno Group', 'https://banno.com'],
  ['Cleverbase', 'https://cleverbase.com/'],
  ['Compstak', 'https://compstak.com'],
  ['Disney Streaming Services', 'https://disneyplus.com'],
  ['Dripower LTD', 'https://zd.drip.im'],
  ['E.ON', 'https://www.eon.com'],
  ['Eloquentix', 'https://eloquentix.com/'],
  ['Evolution Gaming', 'https://eng.evolutiongaming.com/'],
  ['Exelonix', 'https://exelonix.com/'],
  ['Gemini Observatory', 'https://www.gemini.edu'],
  ['ING Bank', 'https://www.ing.com'],
  ['Inner Product', 'https://inner-product.com'],
  ['Intent HQ', 'https://www.intenthq.com/'],
  ['Kazula', 'https://www.kaluza.com'],
  ['Medidata', 'https://www.medidata.com/'],
  ['OVO Energy', 'https://www.ovoenergy.com'],
  ['Philips', 'https://www.philips.com/'],
  ['Precog', 'https://precog.com'],
  ['Prezi', 'https://prezi.com'],
  ['RMS', 'https://www.rms.com/'],
  ['Revonte', 'https://revonte.com'],
  ['Rewards Network', 'https://www.rewardsnetwork.com/'],
  ['ShipReq', 'https://shipreq.com'],
  ['Software Mill', 'https://softwaremill.com'],
  ['SpotX', 'https://www.spotx.tv/'],
  ['Standard Chartered', 'https://www.sc.com'],
  ['Stripe', 'https://stripe.com/'],
  ['Tapad', 'https://www.tapad.com/'],
  ['Tenable', 'https://www.tenable.com'],
  ['Tinkoff', 'https://www.tinkoff.ru/eng/'],
  ['vidIQ', 'https://vidiq.com/'],
  ['XITE', 'https://xite.com/'],
  ['Whisk', 'https://whisk.com'],
  ['Zendesk', 'https://www.zendesk.com'],
];

function Users(props) {
  const renderUsers = users.map((user, i) => (
    <li key={i}>
      <a href={user[1]}>{user[0]}</a>
    </li>
  ));
  return (
    <div className="docMainWrapper wrapper">
      <Container className="mainContainer versionsContainer">
        <div className="post">
          <header className="postHeader">
            <h1>Users</h1>
          </header>

          <p>
            Cats Effect has been deployed at scale for years by countless companies covering nearly
            every segment of the industry. If your company is using Cats Effect and you don't see it
            listed here, feel free to open an issue (or a pull request!) to add it!
          </p>

          <ul>
            {renderUsers}
          </ul>

        </div>
      </Container>
    </div>
  );
}

module.exports = Users;
