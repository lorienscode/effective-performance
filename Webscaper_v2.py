import requests
from bs4 import BeautifulSoup
import sqlite3
import argparse
import sys
import time
import os
import re
import logging

# Set up logging for better error handling and audit trail
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s: %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)

def validate_db_path(db_path):
    # Ensure database path exists and is a file
    if not os.path.exists(db_path) or not os.path.isfile(db_path):
        logging.error(f"Database file does not exist or is not a file: {db_path}")
        sys.exit(1)

def validate_url(url):
    # Allow only proper HTTP/HTTPS URLs
    regex = re.compile(r'^(http|https)://[^\s/$.?#].[^\s]*$')
    if not regex.match(url):
        logging.error(f"Invalid or potentially unsafe URL provided: {url}")
        sys.exit(1)

def fetch_addresses_from_db(db_path):
    """
    Fetches addresses from the specified SQLite database.

    Args:
        db_path (str): The path to the SQLite database.

    Returns:
        list: A list of addresses.
    """
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT address FROM addresses")
        addresses = [row[0] for row in cursor.fetchall()]
        conn.close()
        return addresses
    except sqlite3.OperationalError as e:
        logging.error(f"Error connecting to or querying the database: {e}")
        sys.exit(1)
    except Exception as e:
        logging.error(f"Unexpected error when accessing DB: {e}")
        sys.exit(1)

def scrape_website(url, address, headers, timeout=10):
    """
    Scrapes a website for a given address.

    Args:
        url (str): The URL of the website to scrape.
        address (str): The address to search for.
        headers (dict): The headers to use for the request.
        timeout (int, optional): Timeout for the request in seconds.

    Returns:
        BeautifulSoup: The parsed HTML content of the page, or None if the request fails.
    """
    params = {'q': address}
    try:
        # timeout is added for network robustness
        response = requests.get(url, params=params, headers=headers, timeout=timeout)
        response.raise_for_status()  # Raise an exception for bad status codes
        return BeautifulSoup(response.content, 'html.parser')
    except requests.exceptions.Timeout:
        logging.error(f"Timeout occurred retrieving webpage for address: {address}.")
        return None
    except requests.exceptions.RequestException as e:
        logging.error(f"Failed to retrieve the webpage for address: {address}. Error: {e}")
        return None

def extract_results(soup):
    """
    Extracts search results from the parsed HTML.

    Args:
        soup (BeautifulSoup): The parsed HTML content.

    Yields:
        tuple: A tuple containing the title and link of a search result.
    """
    results = soup.find_all('div', class_='result-class')
    if not results:
        logging.warning("No results found. The website structure might have changed.")
        return

    for result in results:
        try:
            title = result.find('h2').text
            link = result.find('a')['href']
            yield title, link
        except (AttributeError, TypeError) as e:
            logging.warning(f"Error extracting data from a result: {e}")
            continue

def main():
    """
    Main function to orchestrate the web scraping process.
    """
    parser = argparse.ArgumentParser(description="Scrape a website for addresses securely.")
    parser.add_argument("db_path", help="Path to the SQLite database containing addresses.")
    parser.add_argument("--url", default="https://example.com/search", help="URL of the website to scrape.")
    parser.add_argument("--delay", type=float, default=1.0, help="Delay in seconds between requests to avoid overwhelming servers.")
    parser.add_argument("--timeout", type=int, default=10, help="Timeout (seconds) for HTTP requests.")
    args = parser.parse_args()

    # Security: Validate user-provided file and URL arguments
    validate_db_path(args.db_path)
    validate_url(args.url)

    headers = {
        'User-Agent': (
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
            '(KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3'
        )
    }

    addresses = fetch_addresses_from_db(args.db_path)

    for address in addresses:
        soup = scrape_website(args.url, address, headers, timeout=args.timeout)
        if soup:
            logging.info(f"Results for address: {address}")
            for title, link in extract_results(soup):
                logging.info(f"  Title: {title}\n  Link: {link}")
            logging.info("-" * 50)
        # Polite rate limiting between requests
        time.sleep(args.delay)

if __name__ == "__main__":
    main()